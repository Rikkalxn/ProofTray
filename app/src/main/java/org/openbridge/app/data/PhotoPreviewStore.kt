package org.openbridge.app.data

import org.openbridge.app.io.writeAtomically
import android.content.Context
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

    fun candidates(id: String): List<File> {
        if (!id.matches(Regex("[a-fA-F0-9-]{1,128}"))) return emptyList()
        return previewFiles(id).filter { it.isFile && it.length() in 1..MAX_PREVIEW_BYTES }
    }

    private fun previewFiles(id: String): List<File> = listOf("jpg", "heic").flatMap { extension ->
        listOf(File(current, "$id-detail.$extension"), File(saved, "$id.$extension"), File(current, "$id-grid.$extension"))
    }

    fun attachCachedPreview(asset: PhotoAsset): PhotoAsset {
        if (asset.format != MediaFormat.RAW || asset.downloadState != DownloadState.DOWNLOADED) return asset
        // Startup only restores an address; visible previews compare actual dimensions in PhotoPreviewLoader.
        val files = candidates(asset.id)
        val available = files.find { Uri.fromFile(it).toString() == asset.previewUri } ?: files.firstOrNull() ?: return asset
        return withPreview(asset, available)
    }

    fun recover(asset: PhotoAsset): PhotoAsset = synchronized(archiveLock) { recoverLocked(asset) }

    private fun recoverLocked(asset: PhotoAsset): PhotoAsset {
        if (asset.format != MediaFormat.RAW || asset.downloadState != DownloadState.DOWNLOADED) return asset
        if (!asset.id.matches(Regex("[a-fA-F0-9-]{1,128}"))) return asset
        val files = candidates(asset.id)
        val archived = files.find { it.parentFile == saved && asset.previewUri == Uri.fromFile(it).toString() }
        if (archived != null && files.none { it.lastModified() > archived.lastModified() }) return asset
        val best = bestPreviewFile(files)?.first ?: return asset
        val target = File(saved, "${asset.id}.${best.extension}")
        val file = if (best == target) target else {
            try {
                saved.mkdirs()
                target.writeAtomically { output -> best.inputStream().use { it.copyTo(output) } }
                target
            } catch (_: Exception) { best }
        }
        return withPreview(asset, file)
    }

    private fun withPreview(asset: PhotoAsset, file: File) = asset.copy(previewUri = Uri.fromFile(file).toString(),
        previewRevision = "${file.length()}:${file.lastModified()}")

    fun remove(id: String) = synchronized(archiveLock) {
        if (!id.matches(Regex("[a-fA-F0-9-]{1,128}"))) return@synchronized
        previewFiles(id).forEach { AtomicFile(it).delete() }
    }

    companion object {
        private const val MAX_PREVIEW_BYTES = 16L * 1024 * 1024
        private val archiveLock = Any()
    }
}
