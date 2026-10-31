package org.openbridge.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset

data class EditableCopy(val uri: Uri, val mimeType: String)

/** Hands an independently owned copy to ACTION_EDIT. The source URI is never granted for writing. */
class ExternalPhotoEditor(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    suspend fun prepare(asset: PhotoAsset): EditableCopy = withContext(Dispatchers.IO) {
        require(asset.downloadState == DownloadState.DOWNLOADED) { "请先下载原片，再使用系统编辑器。" }
        val source = Uri.parse(asset.uri)
        val extension = asset.name.substringAfterLast('.', "jpg").lowercase().filter(Char::isLetterOrDigit).take(12)
        val mime = when (extension) {
            "nef" -> "image/x-nikon-nef"
            "nrw" -> "image/x-nikon-nrw"
            "dng" -> "image/x-adobe-dng"
            else -> resolver.getType(source)?.takeIf { it.startsWith("image/") } ?: when (asset.format) {
                MediaFormat.PNG -> "image/png"
                MediaFormat.HEIF -> "image/heic"
                MediaFormat.TIFF -> "image/tiff"
                MediaFormat.JPEG -> "image/jpeg"
                MediaFormat.RAW -> "image/x-raw"
                MediaFormat.OTHER -> error("无法确定文件的图片类型，请使用原文件导出")
            }
        }
        val raw = asset.format == MediaFormat.RAW
        val collection = if (raw) MediaStore.Downloads.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val stem = asset.name.substringBeforeLast('.').replace(Regex("[\\\\/:*?\"<>|]"), "_").take(80)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "${stem}_edit_${UUID.randomUUID().toString().take(8)}.$extension")
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH,
                if (raw) "${Environment.DIRECTORY_DOWNLOADS}/OpenBridge/Edits" else "${Environment.DIRECTORY_PICTURES}/OpenBridge/Edits")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val destination = resolver.insert(collection, values) ?: error("无法创建编辑副本")
        var finished = false
        try {
            var copied = 0L
            resolver.openInputStream(source)?.use { input ->
                resolver.openOutputStream(destination, "w")?.use { output ->
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        copied += count
                    }
                } ?: error("无法写入编辑副本")
            } ?: error("原文件不可读，请重新导入")
            check(copied > 0 && (asset.sizeBytes <= 0 || copied == asset.sizeBytes)) { "复制长度与原文件不一致" }
            check(resolver.update(destination, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1) {
                "无法完成编辑副本保存"
            }
            finished = true
            EditableCopy(destination, mime)
        } finally {
            if (!finished) runCatching { resolver.delete(destination, null, null) }
        }
    }
}
