package org.openbridge.app.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.openbridge.app.data.PhotoRepository
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.domain.jpegRepresentationKey

/** Publishes camera-rendered JPEG copies with their own identity; a JPEG copy never marks its RAW original as imported. */
internal class CameraJpegSaver(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val repository = PhotoRepository(context)

    suspend fun save(
        task: CameraDownload, bytes: ByteArray, update: suspend ((CameraDownload) -> CameraDownload) -> Unit, cancelled: () -> Boolean
    ): PhotoAsset {
        currentCoroutineContext().ensureActive()
        if (cancelled()) throw CancellationException("已取消便携 JPEG 下载")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        check(bytes.size in 4..(16 * 1024 * 1024) && bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType == "image/jpeg") {
            "相机未返回有效 JPEG"
        }
        val name = task.photo.asset.name.substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80) + "_OpenBridge_2M.jpg"
        val key = jpegRepresentationKey(task.photo.asset)
        val uri = task.outputUri?.let(Uri::parse) ?: resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/OpenBridge")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: error("无法创建 JPEG 副本")
        try {
            update { it.copy(outputUri = uri.toString(), payloadBytes = bytes.size.toLong(), message = "正在保存便携 JPEG") }
        } catch (error: Exception) {
            if (task.outputUri == null) resolver.delete(uri, null, null)
            throw error
        }
        val descriptor = resolver.openFileDescriptor(uri, "rwt") ?: error("无法写入 JPEG 副本")
        ParcelFileDescriptor.AutoCloseOutputStream(descriptor).use { stream ->
            var offset = 0
            while (offset < bytes.size) {
                currentCoroutineContext().ensureActive()
                if (cancelled()) throw CancellationException("已取消便携 JPEG 下载")
                val count = minOf(128 * 1024, bytes.size - offset)
                stream.write(bytes, offset, count)
                offset += count
            }
            stream.fd.sync()
        }
        update { it.copy(received = bytes.size.toLong(), message = "JPEG 已接收，正在完成保存") }
        check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
            "无法完成 JPEG 媒体库保存"
        }
        val template = task.photo.asset.copy(id = key, name = name, format = MediaFormat.JPEG, cameraFileKey = key,
            inImportList = false, previewUri = null, hiddenFromLibrary = false)
        return repository.readCameraDownload(template, uri).copy(derivedFromId = task.photo.asset.id)
    }
}
