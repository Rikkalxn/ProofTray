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
    private val helper = object : SQLiteOpenHelper(context, "camera-downloads.db", null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE tasks (id TEXT PRIMARY KEY, ordinal INTEGER NOT NULL, payload TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("尚未定义下载队列迁移") }
    }
    private var persisted = emptyMap<String, CameraDownload>()
    fun load(): List<CameraDownload> {
        val records = helper.readableDatabase.query("tasks", arrayOf("payload"), null, null, null, null, "ordinal").use { cursor ->
            buildList { while (cursor.moveToNext()) add(JSONObject(cursor.getString(0))) }
        }
        val input = if (records.isEmpty() && legacy.exists()) {
            val old = JSONArray(legacy.readText())
            (0 until old.length()).map { old.getJSONObject(it) }
        } else records
        val tasks = input.map { item ->
            CameraDownload(
                item.getString("id"), RemotePhoto(RecordCodec.decodeAsset(item.getJSONObject("asset")), item.getLong("handle"),
                    item.getLong("storage"), item.getString("camera"), item.getString("captured")),
                DownloadStatus.valueOf(item.getString("status")), item.optLong("received"),
                item.optString("output").takeIf { it.isNotEmpty() }, item.optString("message"),
                DownloadVariant.valueOf(item.optString("variant", DownloadVariant.ORIGINAL.name)),
                item.optBoolean("automatic", false), item.optLong("payloadBytes", 0),
                DownloadChannel.valueOf(item.optString("channel", DownloadChannel.WIFI.name))
            )
        }
        if (records.isNotEmpty()) persisted = tasks.associateBy { it.id }
        return tasks
    }
    fun save(tasks: List<CameraDownload>) {
        val changed = tasks.withIndex().filter { persisted[it.value.id] != it.value }
        if (changed.isEmpty()) return
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            changed.forEach { (index, task) ->
                val payload = JSONObject().apply {
                    put("id", task.id); put("asset", RecordCodec.encodeAsset(task.photo.asset)); put("handle", task.photo.handle)
                    put("storage", task.photo.storage); put("camera", task.photo.cameraIdentity); put("captured", task.photo.captured)
                    put("status", task.status.name); put("received", task.received); put("output", task.outputUri ?: ""); put("message", task.message)
                    put("variant", task.variant.name); put("automatic", task.automatic); put("payloadBytes", task.payloadBytes)
                    put("channel", task.channel.name)
                }
                val values = ContentValues().apply { put("id", task.id); put("ordinal", index); put("payload", payload.toString()) }
                check(db.insertWithOnConflict("tasks", null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "下载检查点保存失败" }
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        persisted = tasks.associateBy { it.id }
    }
}
