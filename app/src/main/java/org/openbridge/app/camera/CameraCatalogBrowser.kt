package org.openbridge.app.camera

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.openbridge.app.data.PhotoRepository

/** Binds the cancellable catalog reader to one live camera connection, its preview cache and the local database. */
internal class CameraCatalogBrowser(
    context: Context, scope: CoroutineScope, private val operations: Mutex, private val session: () -> CameraBrowseSession?,
    progress: (BrowseProgress) -> Unit, private val changed: suspend () -> Unit
) {
    val photos = ConcurrentHashMap<String, RemotePhoto>()
    private val repository = PhotoRepository.get(context)
    private val writes = Mutex()
    private val previews = CameraPreviewCache(context, repository, scope, operations, session) { photo ->
        writes.withLock {
            repository.mergeCameraAssets(listOf(photo.asset))
            photos[photo.asset.id] = photo
        }
        changed()
    }
    private val reader = CatalogBatchReader(scope, ::source, progress)
    @Volatile private var clearing = false

    suspend fun resetSession() {
        reader.discard().join()
        previews.stopAndJoin()
        photos.clear()
    }

    fun refresh(size: Int?, direction: CatalogDirection, day: SonyCaptureDay? = null, sortChanged: Boolean = false) {
        reader.start(size, direction, reset = true, day = day, sortChanged = sortChanged)
    }

    fun loadMore(size: Int?, direction: CatalogDirection, autoReadId: Long? = null) {
        reader.start(size, direction, reset = false, autoReadId = autoReadId)
    }

    fun stopReading() { reader.pause() }
    fun requestPreview(id: String, detailed: Boolean): () -> Unit = photos[id]?.let { previews.request(it, detailed) } ?: {}

    private fun source(): CatalogReadSource? {
        if (clearing) return null
        val connection = session() ?: return null
        return CatalogReadSource(connection.generation, { current(connection) }, { order, day -> enumerate(connection, order, day) },
            { handle -> readPhoto(connection, handle) }, { batch -> publish(connection, batch) })
    }

    private suspend fun enumerate(connection: CameraBrowseSession, direction: CatalogDirection, day: SonyCaptureDay?): CatalogDirectory {
        val sony = operations.withLock { requireSession(connection); connection.link.sonyCatalogCursor(direction = direction, startAt = day) }
        if (sony != null) return CatalogDirectory(dates = sony.dates, morePages = { sony.hasMore }) {
            operations.withLock { requireSession(connection); connection.link.nextSonyPage(sony) }
        }
        check(day == null) { "这台相机的目录不支持按日期跳转" }
        val storages = operations.withLock { requireSession(connection); connection.link.storageIds() }
        val handles = linkedSetOf<Long>()
        for (storage in storages) {
            currentCoroutineContext().ensureActive()
            val ids = operations.withLock { requireSession(connection); connection.link.objectHandles(storage) }
            handles.addAll(orderedMtpHandles(ids, direction))
        }
        requireSession(connection)
        return CatalogDirectory(handles.toList())
    }

    private suspend fun readPhoto(connection: CameraBrowseSession, handle: Long): RemotePhoto? {
        val info = try { operations.withLock { requireSession(connection); connection.link.objectInfo(handle) } }
        catch (error: PtpResponseException) { if (error.response == 0x2009) return null else throw error }
        requireSession(connection)
        return cameraPhoto(info, handle, connection) { id -> photos[id]?.asset?.previewUri ?: previews.cachedUri(id) }
    }

    private suspend fun publish(connection: CameraBrowseSession, batch: List<RemotePhoto>) {
        requireSession(connection)
        // A re-sort can encounter a thumbnail with richer EXIF that was published during this session.
        writes.withLock {
            val verified = batch.map { photo -> photos[photo.asset.id]?.copy(handle = photo.handle, storage = photo.storage) ?: photo }
            repository.mergeCameraAssets(verified.map { it.asset })
            for (photo in verified) photos[photo.asset.id] = photo
        }
        changed()
    }

    private fun current(connection: CameraBrowseSession) = session()?.generation == connection.generation && connection.link.connected
    private fun requireSession(connection: CameraBrowseSession) = check(current(connection)) { "相机会话已更换或断开" }

    fun stop() { reader.discard(); previews.stop() }

    suspend fun clearImportList(source: String?, selectedIds: Set<String>? = null) {
        val generation = session()?.generation
        clearing = true
        try {
            reader.pause().join()
            if (selectedIds == null) previews.stopAndJoin() else previews.stopAndJoin(selectedIds)
            val cleanup = repository.clearImportList(source, selectedIds)
            previews.clear(cleanup.unusedPreviewIds)
            cleanup.removedIds.forEach { photos.remove(it) }
            if (generation != null) reader.removed(generation, if (selectedIds == null) null else cleanup.removedIds)
            changed()
        } finally { clearing = false }
    }
}
