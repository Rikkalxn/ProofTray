package org.openbridge.app.camera

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.openbridge.app.domain.PhotoAsset

/** Visible items own request leases. Scrolling away cancels queued work; an active PTP transaction is allowed to finish. */
internal class CameraPreviewCache(
    context: Context, private val scope: CoroutineScope, private val operations: Mutex,
    private val session: () -> CameraBrowseSession?, private val publish: suspend (RemotePhoto) -> Unit
) {
    private class Request(val assetId: String, var observers: Int = 1, var started: Boolean = false, var job: Job? = null)

    private val directory = File(context.cacheDir, "camera-previews-v2")
    private val legacy = File(context.cacheDir, "camera-thumbnails")
    private val lock = Any()
    private val requests = mutableMapOf<String, Request>()
    private val completed = linkedSetOf<String>()
    private val decoder = Semaphore(1)

    fun cachedUri(id: String): String? = cachedPreview(id)?.first?.let { Uri.fromFile(it).toString() }

    private fun cachedPreview(id: String): Pair<File, PreviewDimensions>? =
        listOf(file(id, true), file(id, false), File(legacy, "$id.jpg")).mapNotNull { candidate ->
            if (!candidate.isFile || candidate.length() !in 1..(16L * 1024 * 1024)) return@mapNotNull null
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(candidate.absolutePath, bounds)
            PreviewDimensions(bounds.outWidth, bounds.outHeight).takeIf { it.valid }?.let { candidate to it }
        }.maxByOrNull { it.second.pixels }

    fun request(photo: RemotePhoto, detailed: Boolean): () -> Unit {
        val connection = session() ?: return {}
        if (photo.cameraIdentity != connection.sourceId) return {}
        val key = "${connection.generation}:${photo.asset.id}:$detailed"
        val request = synchronized(lock) {
            if (key in completed) return {}
            requests[key]?.takeIf { it.job?.isActive == true }?.also { it.observers++ } ?: Request(photo.asset.id).also { item ->
                requests[key] = item
                item.job = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        delay(if (detailed) 0 else 120)
                        decoder.withPermit {
                            synchronized(lock) { item.started = true }
                            fetch(photo, detailed, connection)
                            synchronized(lock) {
                                completed += key
                                if (completed.size > 4096) completed.remove(completed.first())
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        Log.w("OpenBridgeCamera", "preview unavailable; detail=$detailed", error)
                    } finally {
                        synchronized(lock) { if (requests[key] === item) requests.remove(key) }
                    }
                }
                item.job?.start()
            }
        }
        return {
            synchronized(lock) {
                request.observers--
                if (request.observers == 0 && !request.started) request.job?.cancel()
            }
        }
    }

    private suspend fun fetch(photo: RemotePhoto, detailed: Boolean, connection: CameraBrowseSession) {
        val destination = file(photo.asset.id, detailed)
        val cached = cachedPreview(photo.asset.id)
        if (cached?.second?.sufficient(detailed) != true) {
            val preview = operations.withLock {
                check(session()?.generation == connection.generation) { "相机预览所属会话已失效" }
                connection.link.preview(photo.handle, detailed)
            }
            currentCoroutineContext().ensureActive()
            if (preview.dimensions.improves(cached?.second)) {
                directory.mkdirs()
                val atomic = AtomicFile(destination)
                val stream = atomic.startWrite()
                try { stream.write(preview.bytes); atomic.finishWrite(stream) }
                catch (error: Exception) { atomic.failWrite(stream); throw error }
            }
        }
        currentCoroutineContext().ensureActive()
        if (session()?.generation != connection.generation) return
        val best = cachedPreview(photo.asset.id) ?: return
        val uri = Uri.fromFile(best.first).toString()
        val revision = "${best.first.length()}:${best.first.lastModified()}"
        Log.i("OpenBridgePreview", "cache detail=$detailed size=${best.second.width}x${best.second.height}" +
            " sufficient=${best.second.sufficient(detailed)}")
        val updated = withPreviewMetadata(photo, best.first).copy(uri = uri, previewUri = uri, previewRevision = revision)
        if (photo.asset != updated) publish(photo.copy(asset = updated))
    }

    private fun withPreviewMetadata(photo: RemotePhoto, file: File): PhotoAsset {
        val asset = photo.asset
        val exif = runCatching { file.inputStream().use { ExifInterface(it) } }.getOrNull() ?: return asset
        fun number(tag: String) = exif.getAttributeDouble(tag, Double.NaN).takeIf { it.isFinite() && it > 0 }
        return asset.copy(
            lens = asset.lens.ifBlank { exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim().orEmpty() },
            iso = asset.iso ?: exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0).takeIf { it > 0 },
            aperture = asset.aperture ?: number(ExifInterface.TAG_F_NUMBER),
            exposureSeconds = asset.exposureSeconds ?: number(ExifInterface.TAG_EXPOSURE_TIME),
            focalLengthMm = asset.focalLengthMm ?: number(ExifInterface.TAG_FOCAL_LENGTH)
        )
    }

    fun stop() { synchronized(lock) { requests.values.forEach { it.job?.cancel() } } }

    suspend fun stopAndJoin() {
        val jobs = synchronized(lock) { requests.values.mapNotNull { it.job }.also { requests.clear(); completed.clear() } }
        jobs.forEach { it.cancelAndJoin() }
    }

    suspend fun clear(ids: Set<String>) {
        stopAndJoin(ids)
        ids.forEach { id ->
            require(id.matches(Regex("[a-f0-9]{64}"))) { "预览缓存标识无效" }
            AtomicFile(file(id, false)).delete()
            AtomicFile(file(id, true)).delete()
            File(legacy, "$id.jpg").delete()
            File(legacy, "$id.part").delete()
        }
    }

    suspend fun stopAndJoin(ids: Set<String>) {
        val jobs = synchronized(lock) {
            val matches = requests.filterValues { it.assetId in ids }
            matches.keys.forEach { requests.remove(it) }
            completed.removeAll { it.substringAfter(':').substringBefore(':') in ids }
            matches.values.mapNotNull { it.job }
        }
        jobs.forEach { it.cancelAndJoin() }
    }

    private fun file(id: String, detailed: Boolean) = File(directory, "$id-${if (detailed) "detail" else "grid"}.jpg")
}
