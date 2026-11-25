package org.prooftray.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import org.json.JSONObject
import org.prooftray.app.domain.ExportTask
import org.prooftray.app.domain.DownloadState
import org.prooftray.app.domain.PhotoAsset
import org.prooftray.app.domain.SavedFilter
import org.prooftray.app.domain.jpegRepresentationKey

internal class CatalogDatabase private constructor(context: Context) : SQLiteOpenHelper(context.applicationContext, "prooftray.db", null, 3) {
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
        if (oldVersion < 3) migrateCompanionLinks(db)
    }

    private fun migrateCompanionLinks(db: SQLiteDatabase) {
        val records = db.query("photos", arrayOf("id", "payload"), null, null, null, null, "rowid").use { cursor ->
            buildList { while (cursor.moveToNext()) add(cursor.getString(0) to JSONObject(cursor.getString(1))) }
        }
        val migrated = migrateCameraCompanions(records.map { RecordCodec.decodeAsset(it.second) })
        records.zip(migrated).forEach { (record, asset) ->
            val (id, payload) = record
            fun link(name: String) = if (payload.isNull(name)) null else payload.getString(name)
            if (link("derivedFromId") != asset.derivedFromId || link("companionOfId") != asset.companionOfId) {
                payload.put("derivedFromId", asset.derivedFromId ?: JSONObject.NULL)
                payload.put("companionOfId", asset.companionOfId ?: JSONObject.NULL)
                // Update only the two links; preserve every other payload field, URI, row order and import-history entry.
                val values = ContentValues().apply { put("payload", payload.toString()) }
                check(db.update("photos", values, "id = ?", arrayOf(id)) == 1) { "无法更新便携 JPEG 的来源关联" }
            }
        }
    }

    fun loadAssets(): List<PhotoAsset> = readableDatabase.query("photos", arrayOf("payload"), null, null, null, null, "rowid").use { cursor ->
        val history = importHistory()
        buildList {
            while (cursor.moveToNext()) {
                val asset = RecordCodec.decodeAsset(JSONObject(cursor.getString(0)))
                add(asset.copy(importedAtMillis = asset.importedAtMillis ?: asset.cameraFileKey?.let(history::get),
                    jpegCopyImportedAtMillis = if (asset.derivedFromId == null && asset.companionOfId == null) {
                        history[jpegRepresentationKey(asset)]
                    } else null))
            }
        }
    }

    private fun createImportHistory(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE camera_imports (file_key TEXT PRIMARY KEY, imported_at INTEGER NOT NULL)")
    }

    fun importHistory(): Map<String, Long> = readableDatabase.query(
        "camera_imports", arrayOf("file_key", "imported_at"), null, null, null, null, null
    ).use { cursor -> buildMap { while (cursor.moveToNext()) put(cursor.getString(0), cursor.getLong(1)) } }

    fun importHistoryCount(): Long = readableDatabase.rawQuery("SELECT COUNT(*) FROM camera_imports", null).use {
        check(it.moveToFirst())
        it.getLong(0)
    }

    fun clearImportHistory(db: SQLiteDatabase = writableDatabase) { db.delete("camera_imports", null, null) }

    fun updateAssetPayload(asset: PhotoAsset, db: SQLiteDatabase) {
        val values = ContentValues().apply { put("payload", RecordCodec.encodeAsset(asset).toString()) }
        check(db.update("photos", values, "id = ?", arrayOf(asset.id)) == 1) { "无法更新照片导入标记" }
    }

    fun rememberImport(key: String, timestamp: Long, db: SQLiteDatabase = writableDatabase) {
        val values = ContentValues().apply { put("file_key", key); put("imported_at", timestamp) }
        db.insertWithOnConflict("camera_imports", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun deleteAsset(id: String, db: SQLiteDatabase = writableDatabase) { db.delete("photos", "id = ?", arrayOf(id)) }

    fun assetForUri(uri: String): PhotoAsset? = readableDatabase.query(
        "photos", arrayOf("payload"), "uri = ?", arrayOf(uri), null, null, null
    ).use { cursor -> if (cursor.moveToFirst()) RecordCodec.decodeAsset(JSONObject(cursor.getString(0))) else null }

    fun assetForDocument(document: Uri): PhotoAsset? {
        assetForUri(document.toString())?.let { return it }
        val prefix = "content://${document.encodedAuthority}/tree/"
        val suffix = requireNotNull(document.encodedPath)
        val args = arrayOf(prefix.length.toString(), prefix, suffix.length.toString(), suffix)
        return readableDatabase.query("photos", arrayOf("payload"), "substr(uri, 1, ?) = ? AND substr(uri, -?) = ?", args,
            null, null, null, "1").use { cursor -> if (cursor.moveToFirst()) RecordCodec.decodeAsset(JSONObject(cursor.getString(0))) else null }
    }

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

    fun updateSourceGeometry(source: PhotoAsset): PhotoAsset? {
        val stored = assetForId(source.id) ?: return null
        val updated = stored.withGeometryFrom(source)
        if (updated == stored) return null
        val values = ContentValues().apply { put("payload", RecordCodec.encodeAsset(updated).toString()) }
        // UPDATE keeps rowid/order and writes only the latest row with its three geometry fields replaced.
        check(writableDatabase.update("photos", values, "id = ? AND uri = ?", arrayOf(stored.id, stored.uri)) == 1) {
            "无法更新照片方向"
        }
        return updated
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
        // Rows written by earlier development builds may not decode; they are skipped, not deleted.
        buildList { while (cursor.moveToNext()) runCatching { RecordCodec.decodeTask(JSONObject(cursor.getString(0))) }.onSuccess(::add) }
    }

    fun putTasks(tasks: List<ExportTask>, removedIds: List<String> = emptyList()) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            // Restore can skip unreadable rows. Only an explicit history cleanup owns their removal.
            removedIds.forEach { db.delete("export_tasks", "id = ?", arrayOf(it)) }
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

    companion object {
        @Volatile private var instance: CatalogDatabase? = null
        fun get(context: Context): CatalogDatabase = instance ?: synchronized(this) {
            instance ?: CatalogDatabase(context.applicationContext).also { instance = it }
        }
    }
}
