package org.openbridge.app.camera

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openbridge.app.data.PhotoRepository
import org.openbridge.app.domain.PhotoAsset

/** Enumerates handles once, then reads a bounded batch of ObjectInfo records on demand. */
internal class CameraCatalogBrowser(
    context: Context, private val scope: CoroutineScope, private val operations: Mutex, private val session: () -> CameraBrowseSession?,
    private val progress: (BrowseProgress) -> Unit, private val changed: suspend () -> Unit
) {
    val photos = ConcurrentHashMap<String, RemotePhoto>()
    private val repository = PhotoRepository(context)
    private val previews = CameraPreviewCache(context, scope, operations, session) { photo ->
        photos[photo.asset.id] = photo
        repository.mergeCameraAssets(listOf(photo.asset))
        changed()
    }
    private var job: Job? = null
    private var handles = emptyList<Long>()
    private var cursor = 0
    private var generation = -1
    @Volatile private var clearing = false
    private val loadedIds = linkedSetOf<String>()

    suspend fun resetSession() {
        job?.cancelAndJoin()
        previews.stopAndJoin()
        photos.clear()
        handles = emptyList()
        cursor = 0
        generation = -1
        loadedIds.clear()
    }

    fun refresh(batchSize: Int) = readBatch(batchSize, reset = true)
    fun loadMore(batchSize: Int) = readBatch(batchSize, reset = false)
    fun requestPreview(id: String, detailed: Boolean): () -> Unit = photos[id]?.let { previews.request(it, detailed) } ?: {}

    @Synchronized
    private fun readBatch(batchSize: Int, reset: Boolean) {
        require(batchSize in setOf(25, 50, 100, 200))
        if (clearing || job?.isActive == true) return
        val connection = session() ?: return
        job = scope.launch {
            try {
                val restarting = reset || generation != connection.generation
                if (restarting) { loadedIds.clear(); handles = emptyList(); cursor = 0; generation = -1 }
                report(connection.generation, busy = true)
                if (restarting) enumerate(connection)
                val end = minOf(cursor + batchSize, handles.size)
                val batch = mutableListOf<PhotoAsset>()
                var position = cursor
                while (position < end) {
                    currentCoroutineContext().ensureActive()
                    requireSession(connection)
                    val handle = handles[position]
                    try {
                        val info = operations.withLock { requireSession(connection); connection.link.objectInfo(handle) }
                        currentCoroutineContext().ensureActive()
                        requireSession(connection)
                        cameraPhoto(info, handle, connection, previews::cachedUri)?.let { photo ->
                            photos[photo.asset.id] = photo
                            batch += photo.asset
                        }
                    } catch (error: PtpResponseException) {
                        if (error.response != 0x2009) throw error
                    }
                    position++
                    if (batch.size >= 8 || position == end) {
                        publish(batch, connection)
                        loadedIds.addAll(batch.map { it.id })
                        cursor = position
                        batch.clear()
                        report(connection.generation, busy = true)
                    }
                }
                report(connection.generation, busy = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (session()?.generation == connection.generation) {
                    report(connection.generation, busy = false, message = error.message)
                }
            }
        }
    }

    private suspend fun enumerate(connection: CameraBrowseSession) {
        val storageIds = operations.withLock { requireSession(connection); connection.link.storageIds() }
        val discovered = mutableListOf<Long>()
        for (storage in storageIds) {
            val ids = operations.withLock { requireSession(connection); connection.link.objectHandles(storage) }
            // Handle order is an enumeration hint, not a promise that these are the newest photos on the whole card.
            discovered.addAll(ids.reversedArray().toList())
        }
        requireSession(connection)
        handles = discovered.distinct()
        cursor = 0
        generation = connection.generation
    }

    private suspend fun publish(batch: List<PhotoAsset>, connection: CameraBrowseSession) {
        if (batch.isEmpty()) return
        requireSession(connection)
        repository.mergeCameraAssets(batch.toList())
        changed()
    }

    private fun requireSession(connection: CameraBrowseSession) {
        check(session()?.generation == connection.generation && connection.link.connected) { "相机会话已更换或断开" }
    }

    private fun report(ownerGeneration: Int, busy: Boolean, message: String? = null) {
        progress(BrowseProgress(ownerGeneration, cursor, handles.size, busy, cursor < handles.size, loadedIds.toSet(), message))
    }

    fun stop() { job?.cancel(); previews.stop() }

    suspend fun clearImportList(source: String?, selectedIds: Set<String>? = null) {
        val ownerGeneration = session()?.generation
        clearing = true
        try {
            job?.cancelAndJoin()
            if (selectedIds == null) previews.stopAndJoin() else previews.stopAndJoin(selectedIds)
            val cleanup = repository.clearImportList(source, selectedIds)
            previews.clear(cleanup.unusedPreviewIds)
            cleanup.removedIds.forEach { photos.remove(it) }
            if (selectedIds == null) {
                handles = emptyList()
                cursor = 0
                generation = -1
                loadedIds.clear()
            } else loadedIds.removeAll(cleanup.removedIds)
            changed()
        } finally {
            clearing = false
            ownerGeneration?.let { report(it, busy = false) }
        }
    }
}
