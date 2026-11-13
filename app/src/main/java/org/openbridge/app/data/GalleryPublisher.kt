package org.openbridge.app.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.openbridge.app.domain.photoMimeType

/** Publishes completed images and requests a media scan so gallery apps can refresh their indexes. */
internal class GalleryPublisher(context: Context) {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver
    private val preferences = appContext.getSharedPreferences("gallery-index", Context.MODE_PRIVATE)
    private val scanScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun publish(output: Uri) {
        check(resolver.update(output, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
            "图片写入完成，但相册保存未完成"
        }
        // Scanning is best effort after publication; a scan failure must never discard an already published photo.
        scanScope.launch { scanPublished(listOf(output)) }
    }

    fun restoreIndexOnce(key: String, outputs: List<Uri>) {
        if (preferences.getBoolean(key, false)) return
        scanPublished(outputs) { success ->
            if (success) preferences.edit().putBoolean(key, true).apply()
        }
    }

    private data class ScanFile(val path: String, val mime: String)

    @Suppress("DEPRECATION") // MediaScannerConnection needs the path; access remains limited to our recorded MediaStore outputs.
    private fun scanPublished(outputs: List<Uri>, finished: (Boolean) -> Unit = {}) {
        val failed = AtomicBoolean(false)
        val projection = arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.IS_PENDING)
        val files = outputs.distinct().mapNotNull { output ->
            if (output.scheme != "content" || output.authority != MediaStore.AUTHORITY) return@mapNotNull null
            try {
                val record = resolver.query(output, projection, null, null, null)
                if (record == null) { failed.set(true); return@mapNotNull null }
                record.use { cursor ->
                    // Size and dimensions can be missing until indexing completes; do not use them to skip the repair scan.
                    if (!cursor.moveToFirst() || cursor.isNull(0) || cursor.isNull(2) || cursor.getInt(2) != 0) {
                        return@use null
                    }
                    val path = cursor.getString(0).takeIf(String::isNotBlank) ?: return@use null
                    val mime = cursor.getString(1)?.takeIf(String::isNotBlank) ?: photoMimeType(path) ?: "application/octet-stream"
                    ScanFile(path, mime)
                }
            } catch (error: Exception) {
                failed.set(true)
                Log.w("ProofTray.Gallery", "Cannot inspect a published image for media scanning", error)
                null
            }
        }.distinctBy { it.path }
        if (files.isEmpty()) { finished(!failed.get()); return }
        val remaining = AtomicInteger(files.size)
        try {
            MediaScannerConnection.scanFile(appContext, files.map { it.path }.toTypedArray(), files.map { it.mime }.toTypedArray()) { _, uri ->
                if (uri == null) {
                    failed.set(true)
                    Log.w("ProofTray.Gallery", "Media scan did not return an indexed image")
                } else try { resolver.notifyChange(uri, null) } catch (error: Exception) {
                    failed.set(true)
                    Log.w("ProofTray.Gallery", "Cannot notify the gallery after media scanning", error)
                }
                if (remaining.decrementAndGet() == 0) finished(!failed.get())
            }
        } catch (error: Exception) {
            Log.w("ProofTray.Gallery", "Cannot request media scanning", error)
            finished(false)
        }
    }

    companion object {
        val collection: Uri get() = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath: String = "${Environment.DIRECTORY_PICTURES}/ProofTray"
    }
}
