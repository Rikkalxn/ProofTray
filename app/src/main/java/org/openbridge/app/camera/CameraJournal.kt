package org.openbridge.app.camera

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.openbridge.app.data.RecordCodec
import org.openbridge.app.domain.DownloadVariant

internal class CameraJournal(context: Context) {
    private val legacy = File(context.filesDir, "camera-downloads.json")
    private val helper = object : SQLiteOpenHelper(context, "camera-downloads.db", null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY, ordinal INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE journal_meta (name TEXT PRIMARY KEY)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) db.execSQL("CREATE TABLE journal_meta (name TEXT PRIMARY KEY)")
        }
    }
    private var persisted = emptyMap<String, CameraDownload>()
    private var legacyMigrated = false
    fun load(): List<CameraDownload> {
        legacyMigrated = helper.readableDatabase.rawQuery("SELECT name FROM journal_meta WHERE name = 'legacy-migrated'", null).use {
            it.moveToFirst()
        }
        val records = helper.readableDatabase.query("tasks", arrayOf("payload"), null, null, null, null, "ordinal").use { cursor ->
            buildList { while (cursor.moveToNext()) add(JSONObject(cursor.getString(0))) }
        }
        val input = if (!legacyMigrated && records.isEmpty() && legacy.exists()) {
            val old = JSONArray(legacy.readText())
            (0 until old.length()).map { old.getJSONObject(it) }
        } else records
        val tasks = input.map { item ->
            CameraDownload(
                item.getString("id"), RemotePhoto(RecordCodec.decodeAsset(item.getJSONObject("asset")), item.getLong("handle"),
                    item.getLong("storage"), item.getString("camera"), item.getString("captured")),
                DownloadStatus.valueOf(item.getString("status")), item.optLong("received"),
                item.optString("output").takeIf { it.isNotEmpty() }, restoredCameraMessage(item.optString("message")),
                DownloadVariant.valueOf(item.optString("variant", DownloadVariant.ORIGINAL.name)),
                item.optBoolean("automatic", false), item.optLong("payloadBytes", 0),
                DownloadChannel.valueOf(item.optString("channel", DownloadChannel.WIFI.name))
            )
        }
        if (records.isNotEmpty()) persisted = tasks.associateBy { it.id }
        return tasks
    }
    fun save(tasks: List<CameraDownload>) {
        val positions = persisted.keys.withIndex().associate { it.value to it.index }
        val changed = tasks.withIndex().filter { persisted[it.value.id] != it.value || positions[it.value.id] != it.index }
        val currentIds = tasks.mapTo(hashSetOf()) { it.id }
        val removed = persisted.keys - currentIds
        if (changed.isEmpty() && removed.isEmpty() && legacyMigrated) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            removed.forEach { db.delete("tasks", "id = ?", arrayOf(it)) }
            changed.forEach { (index, task) ->
                val payload = JSONObject().apply {
                    put("id", task.id); put("asset", RecordCodec.encodeAsset(task.photo.asset)); put("handle", task.photo.handle)
                    put("storage", task.photo.storage); put("camera", task.photo.cameraIdentity); put("captured", task.photo.captured)
                    put("status", task.status.name); put("received", task.received); put("output", task.outputUri ?: ""); put("message", task.message)
                    put("variant", task.variant.name); put("automatic", task.automatic); put("payloadBytes", task.payloadBytes)
                    put("channel", task.channel.name)
                }
                val values = ContentValues().apply { put("id", task.id); put("ordinal", index); put("payload", payload.toString()) }
                check(db.insertWithOnConflict("tasks", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "导入检查点保存失败" }
            }
            db.execSQL("INSERT OR IGNORE INTO journal_meta (name) VALUES ('legacy-migrated')")
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        persisted = tasks.associateBy { it.id }
        legacyMigrated = true
    }
}
