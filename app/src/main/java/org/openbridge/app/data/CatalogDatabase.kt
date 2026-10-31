package org.openbridge.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import org.openbridge.app.domain.ExportTask
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.domain.SavedFilter
import org.openbridge.app.domain.jpegRepresentationKey

internal class CatalogDatabase(context: Context) : SQLiteOpenHelper(context.applicationContext, "openbridge.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE photos (id TEXT PRIMARY KEY, uri TEXT NOT NULL UNIQUE, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE filters (id TEXT PRIMARY KEY, name TEXT NOT NULL, payload TEXT NOT NULL)")
        db.execSQL("CREATE TABLE export_tasks (id TEXT PRIMARY KEY, position INTEGER NOT NULL, payload TEXT NOT NULL)")
        createImportHistory(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            createImportHistory(db)
            db.query("photos", arrayOf("payload"), null, null, null, null, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val asset = RecordCodec.decodeAsset(JSONObject(cursor.getString(0)))
                    if (asset.downloadState == DownloadState.DOWNLOADED) {
                        asset.cameraFileKey?.let { rememberImport(it, asset.importedAtMillis ?: System.currentTimeMillis(), db) }
                    }
                }
            }
        }
    }

    fun loadAssets(): List<PhotoAsset> = readableDatabase.query("photos", arrayOf("payload"), null, null, null, null, "rowid").use { cursor ->
        val history = importHistory()
        buildList {
            while (cursor.moveToNext()) {
                val asset = RecordCodec.decodeAsset(JSONObject(cursor.getString(0)))
                add(asset.copy(importedAtMillis = asset.importedAtMillis ?: asset.cameraFileKey?.let(history::get),
                    jpegCopyImportedAtMillis = if (asset.derivedFromId == null) history[jpegRepresentationKey(asset)] else null))
            }
        }
    }

    private fun createImportHistory(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE camera_imports (file_key TEXT PRIMARY KEY, imported_at INTEGER NOT NULL)")
    }

    fun importHistory(): Map<String, Long> = readableDatabase.query(
        "camera_imports", arrayOf("file_key", "imported_at"), null, null, null, null, null
    ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getLong(1)) } }

    fun rememberImport(key: String, timestamp: Long, db: SQLiteDatabase = writableDatabase) {
        val values = ContentValues().apply { put("file_key", key); put("imported_at", timestamp) }
        db.insertWithOnConflict("camera_imports", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun deleteAsset(id: String, db: SQLiteDatabase = writableDatabase) { db.delete("photos", "id = ?", arrayOf(id)) }

    fun assetForUri(uri: String): PhotoAsset? = readableDatabase.query(
        "photos", arrayOf("payload"), "uri = ?", arrayOf(uri), null, null, null
    ).use { cursor -> if (cursor.moveToFirst()) RecordCodec.decodeAsset(JSONObject(cursor.getString(0))) else null }

    fun assetForId(id: String): PhotoAsset? = readableDatabase.query(
        "photos", arrayOf("payload"), "id = ?", arrayOf(id), null, null, null
    ).use { cursor -> if (cursor.moveToFirst()) RecordCodec.decodeAsset(JSONObject(cursor.getString(0))) else null }

    fun putAsset(asset: PhotoAsset, db: SQLiteDatabase = writableDatabase) {
        val values = ContentValues().apply {
            put("id", asset.id)
            put("uri", asset.uri)
            put("payload", RecordCodec.encodeAsset(asset).toString())
        }
        check(db.insertWithOnConflict("photos", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "保存照片目录失败" }
    }

    fun loadFilters(): List<SavedFilter> = readableDatabase.query(
        "filters", arrayOf("id", "name", "payload"), null, null, null, null, "name COLLATE NOCASE"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                val filter = RecordCodec.decodeFilter(JSONObject(cursor.getString(2)))
                add(SavedFilter(cursor.getString(0), cursor.getString(1), filter))
            }
        }
    }

    fun putFilter(filter: SavedFilter) {
        val values = ContentValues().apply {
            put("id", filter.id)
            put("name", filter.name.trim())
            put("payload", RecordCodec.encodeFilter(filter.spec).toString())
        }
        check(writableDatabase.insertWithOnConflict("filters", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "保存筛选失败" }
    }

    fun deleteFilter(id: String) { writableDatabase.delete("filters", "id = ?", arrayOf(id)) }

    fun loadTasks(): List<ExportTask> = readableDatabase.query(
        "export_tasks", arrayOf("payload"), null, null, null, null, "position"
    ).use { cursor ->
        buildList { while (cursor.moveToNext()) add(RecordCodec.decodeTask(JSONObject(cursor.getString(0)))) }
    }

    fun putTasks(tasks: List<ExportTask>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            tasks.forEachIndexed { index, task ->
                putTask(task, index, db)
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun putTask(task: ExportTask, position: Int, db: SQLiteDatabase = writableDatabase) {
        val values = ContentValues().apply {
            put("id", task.id)
            put("position", position)
            put("payload", RecordCodec.encodeTask(task).toString())
        }
        check(db.insertWithOnConflict("export_tasks", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "保存导出任务失败" }
    }
}
