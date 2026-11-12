package org.openbridge.app.data

import android.content.Context
import android.net.Uri
import android.system.Os
import android.system.OsConstants
import android.util.LruCache
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset

/** Shared by grids, viewers, comparisons and task rows, regardless of how the original entered the library. */
internal object PhotoPreviewLoader {
    private val cache = object : LruCache<String, LoadedPhotoPreview>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: LoadedPhotoPreview): Int = value.bitmap.allocationByteCount
    }
    private val loads = Array(16) { Mutex() }

    fun clearMemoryCache() = cache.evictAll()

    suspend fun load(
        context: Context, asset: PhotoAsset, edge: Int, onGeometry: (PhotoAsset) -> Unit = {}, onPreview: (LoadedPhotoPreview) -> Unit = {}
    ): LoadedPhotoPreview? {
        val rawUri = localRawUri(context, asset)
        val original = rawUri?.let { localRawGeometry(context, it) }
        val resolved = original?.applyTo(asset) ?: asset
        if (resolved != asset) onGeometry(resolved)
        val sourceKey = sourceIdentity(context, resolved)
        val local = asset.downloadState == DownloadState.DOWNLOADED && (asset.format != MediaFormat.RAW || rawUri != null)
        val uris = if (local && asset.format != MediaFormat.RAW) listOf(asset.uri) else cameraSources(context, resolved)
        val revisions = uris.map { uri ->
            val file = Uri.parse(uri).takeIf { it.scheme == "file" }?.path?.let(::File)
            "$uri:${file?.length()}:${file?.lastModified()}"
        }
        val key = "$sourceKey:$revisions:${asset.previewRevision}:$edge:$original:" +
            "${resolved.sourceOrientation}:${resolved.width}:${resolved.height}:${asset.format}:${asset.downloadState}"
        return loads[(key.hashCode() and Int.MAX_VALUE) % loads.size].withLock {
            currentCoroutineContext().ensureActive()
            cache.get(key)?.let { return@withLock it }
            val origin = if (local && asset.format != MediaFormat.RAW) PreviewOrigin.ORIGINAL else PreviewOrigin.CAMERA
            val candidates = uris.mapNotNull { PreviewCandidate.fromUri(context, it, origin) }
            var best = decodeBest(context, candidates, resolved, original, edge)
            if (local && asset.format == MediaFormat.RAW && best?.dimensions?.covers(edge) != true) {
                best?.let(onPreview)
                val embedded = RawPreviewExtractor.load(context, resolved, sourceKey)
                if (embedded != null && embedded.info.dimensions.improves(best?.dimensions)) {
                    decodeBest(context, listOf(embedded), resolved, original, edge)?.let { best = it }
                }
                if (best?.dimensions?.sufficient(edge > 768) != true) {
                    val fallback = RawPreviewExtractor.fallback(context, resolved)
                    if (fallback != null && fallback.info.dimensions.improves(best?.dimensions)) {
                        decodeBest(context, listOf(fallback), resolved, original, edge)?.let { best = it }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            best?.also { cache.put(key, it) }
        }
    }

    private fun cameraSources(context: Context, asset: PhotoAsset): List<String> {
        val store = PhotoPreviewStore(context)
        val recovered = store.recover(asset)
        return (listOfNotNull(recovered.previewUri, asset.previewUri,
            asset.uri.takeIf { asset.downloadState != DownloadState.DOWNLOADED && it.startsWith("file:") }) +
            store.candidates(asset.id).map { Uri.fromFile(it).toString() }).distinct()
    }

    private suspend fun decodeBest(
        context: Context, candidates: List<PreviewCandidate>, asset: PhotoAsset, original: SourceOrientation?, edge: Int
    ): LoadedPhotoPreview? {
        for (candidate in candidates.sortedByDescending { it.info.dimensions.pixels }) {
            currentCoroutineContext().ensureActive()
            try { PreviewBitmapDecoder.decode(context, candidate, asset, original, edge)?.let { return it } }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* A corrupt or stale preview must not hide a usable alternative. */ }
        }
        return null
    }

    private fun sourceIdentity(context: Context, asset: PhotoAsset): String {
        val uri = Uri.parse(asset.uri)
        val revision = if (asset.downloadState == DownloadState.DOWNLOADED) try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use {
                val stat = Os.fstat(it.fileDescriptor)
                if (OsConstants.S_ISREG(stat.st_mode)) "${stat.st_size}:${stat.st_mtime}:${stat.st_ctime}" else null
            }
        } catch (_: Exception) { null } else null
        return "${asset.uri}:${asset.sizeBytes}:$revision"
    }
}
