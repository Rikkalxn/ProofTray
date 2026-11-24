package org.prooftray.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import java.io.FileNotFoundException
import org.prooftray.app.domain.ExportDestination
import org.prooftray.app.domain.ExportFormat
import org.prooftray.app.domain.ExportTask
import org.prooftray.app.domain.photoMimeType

/** Owns only newly created export outputs; published gallery items are never removed by recovery. */
internal class ExportOutputStore(context: Context) {
    private val resolver = context.applicationContext.contentResolver
    private val gallery = GalleryPublisher(context)

    fun publish(task: ExportTask, output: Uri) {
        if (task.options.destination != ExportDestination.GALLERY) return
        gallery.publish(output)
    }

    fun restoreGalleryIndex(outputs: List<Uri>) = gallery.restoreIndexOnce("exports-v1", outputs)

    fun create(task: ExportTask): Uri {
        if (task.options.destination == ExportDestination.GALLERY) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, outputName(task))
                put(MediaStore.MediaColumns.MIME_TYPE, outputMime(task))
                put(MediaStore.MediaColumns.RELATIVE_PATH, GalleryPublisher.relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                task.asset.capturedAtMillis?.let { put(MediaStore.Images.Media.DATE_TAKEN, it) }
            }
            return resolver.insert(GalleryPublisher.collection, values) ?: error("无法在相册创建导出文件")
        }
        val tree = Uri.parse(task.destinationUri)
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return DocumentsContract.createDocument(resolver, parent, outputMime(task), outputName(task)) ?: error("无法创建导出文件")
    }

    private fun mediaState(uri: Uri): OutputState {
        return resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_PENDING, MediaStore.MediaColumns.SIZE), null, null, null)?.use {
            when {
                !it.moveToFirst() -> OutputState.ABSENT
                it.isNull(0) -> OutputState.UNKNOWN
                it.getInt(0) == 1 -> OutputState.PENDING
                it.getInt(0) == 0 && !it.isNull(1) && it.getLong(1) > 0 -> OutputState.PUBLISHED
                else -> OutputState.UNKNOWN
            }
        } ?: OutputState.UNKNOWN
    }

    fun clean(value: String?): OutputState {
        val uri = value?.let(Uri::parse) ?: return OutputState.ABSENT
        if (uri.authority == MediaStore.AUTHORITY) return cleanPendingMedia({ mediaState(uri) }) {
            resolver.delete(uri, "${MediaStore.MediaColumns.IS_PENDING} = ?", arrayOf("1"))
        }
        return try {
            if (DocumentsContract.deleteDocument(resolver, uri)) OutputState.ABSENT else {
                resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) OutputState.UNKNOWN else OutputState.ABSENT
                } ?: OutputState.UNKNOWN
            }
        } catch (_: FileNotFoundException) {
            OutputState.ABSENT
        } catch (_: Exception) {
            OutputState.UNKNOWN
        }
    }

    private fun outputName(task: ExportTask): String {
        val stem = task.asset.name.substringBeforeLast('.', task.asset.name).replace(Regex("[\\\\/:*?\"<>|]"), "_").take(96)
        val extension = when (task.options.format) {
            ExportFormat.ORIGINAL -> task.asset.name.substringAfterLast('.', "bin").filter(Char::isLetterOrDigit).take(12).ifBlank { "bin" }
            ExportFormat.JPEG -> "jpg"
            ExportFormat.PNG -> "png"
            ExportFormat.TIFF -> "tif"
        }
        val suffix = task.options.log?.encoding?.name?.let { "_${it}_Log16" }.orEmpty()
        return "${stem}_ProofTray${suffix}_${task.id.take(8)}.$extension"
    }

    private fun outputMime(task: ExportTask): String = when (task.options.format) {
        ExportFormat.JPEG -> "image/jpeg"
        ExportFormat.PNG -> "image/png"
        ExportFormat.TIFF -> "image/tiff"
        ExportFormat.ORIGINAL -> photoMimeType(task.asset.name) ?: resolver.getType(Uri.parse(task.asset.uri)) ?: "application/octet-stream"
    }
}
