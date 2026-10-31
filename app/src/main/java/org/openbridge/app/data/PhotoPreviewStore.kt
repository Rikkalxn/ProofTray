package org.openbridge.app.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset

/** Keeps a preview beside the app's catalog once a RAW original is local. Never modifies the original URI. */
internal class PhotoPreviewStore(context: Context) {
    private val saved = File(context.filesDir, "photo-previews")
    private val current = File(context.cacheDir, "camera-previews-v2")
    private val legacy = File(context.cacheDir, "camera-thumbnails")

    fun candidates(id: String): List<File> {
        if (!id.matches(Regex("[a-fA-F0-9-]{1,128}"))) return emptyList()
        return listOf(File(current, "$id-detail.jpg"), File(saved, "$id.jpg"), File(current, "$id-grid.jpg"), File(legacy, "$id.jpg"))
            .filter { it.isFile && it.length() in 1..MAX_PREVIEW_BYTES }
    }

    fun attachCachedPreview(asset: PhotoAsset): PhotoAsset {
        if (asset.format != MediaFormat.RAW || asset.downloadState != DownloadState.DOWNLOADED) return asset
        val files = candidates(asset.id)
        val available = files.find { Uri.fromFile(it).toString() == asset.previewUri } ?: files.firstOrNull() ?: return asset
        return withPreview(asset, available)
    }

    fun recover(asset: PhotoAsset): PhotoAsset = synchronized(archiveLock) { recoverLocked(asset) }

    private fun recoverLocked(asset: PhotoAsset): PhotoAsset {
        if (asset.format != MediaFormat.RAW || asset.downloadState != DownloadState.DOWNLOADED) return asset
        if (!asset.id.matches(Regex("[a-fA-F0-9-]{1,128}"))) return asset
        val target = File(saved, "${asset.id}.jpg")
        if (asset.previewUri == Uri.fromFile(target).toString() && target.isFile && target.length() in 1..MAX_PREVIEW_BYTES &&
            candidates(asset.id).none { it != target && it.lastModified() > target.lastModified() }) return asset
        val available = candidates(asset.id).mapNotNull { file ->
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) null else file to options.outWidth.toLong() * options.outHeight
        }
        val best = available.maxByOrNull { it.second }?.first ?: return asset
        val file = if (best == target) target else {
            try {
                saved.mkdirs()
                val atomic = AtomicFile(target)
                val output = atomic.startWrite()
                try {
                    best.inputStream().use { it.copyTo(output) }
                    atomic.finishWrite(output)
                } catch (error: Exception) { atomic.failWrite(output); throw error }
                target
            } catch (_: Exception) { best }
        }
        return withPreview(asset, file)
    }

    private fun withPreview(asset: PhotoAsset, file: File) = asset.copy(previewUri = Uri.fromFile(file).toString(),
        previewRevision = "${file.length()}:${file.lastModified()}")

    fun remove(id: String) = synchronized(archiveLock) {
        if (!id.matches(Regex("[a-fA-F0-9-]{1,128}"))) return@synchronized
        listOf(File(saved, "$id.jpg"), File(current, "$id-detail.jpg"), File(current, "$id-grid.jpg"), File(legacy, "$id.jpg"))
            .forEach { AtomicFile(it).delete() }
    }

    companion object {
        private const val MAX_PREVIEW_BYTES = 16L * 1024 * 1024
        private val archiveLock = Any()
    }
}
