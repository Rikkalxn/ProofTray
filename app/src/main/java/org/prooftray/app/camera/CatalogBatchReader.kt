package org.prooftray.app.camera

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class CatalogDirectory(
    val initialHandles: List<Long> = emptyList(), val dates: List<SonyCaptureDay> = emptyList(),
    val morePages: () -> Boolean = { false }, val nextPage: suspend () -> List<Long> = { emptyList() }
)

internal class CatalogReadSource(
    val generation: Int, val current: () -> Boolean, val open: suspend (CatalogDirection, SonyCaptureDay?) -> CatalogDirectory,
    val read: suspend (Long) -> RemotePhoto?, val publish: suspend (List<RemotePhoto>) -> Unit
)

/** Commands take the mutex in submission order. Only the reader touches traversal data until cancelAndJoin completes. */
internal class CatalogBatchReader(private val scope: CoroutineScope, private val source: () -> CatalogReadSource?,
    private val progress: (BrowseProgress) -> Unit) {
    private class Traversal(val source: CatalogReadSource, val direction: CatalogDirection, val day: SonyCaptureDay?, val id: Long) {
        var directory: CatalogDirectory? = null
        val handles = mutableListOf<Long>()
        val loaded = linkedSetOf<String>()
        var visible = emptySet<String>()
        var cursor = 0
        @Volatile var stopping = false
        val hasMore get() = directory == null || cursor < handles.size || directory?.morePages() == true
    }

    private val commands = Mutex()
    private var worker: Job? = null
    private var traversal: Traversal? = null
    private var revision = 0L
    @Volatile private var last: BrowseProgress? = null

    fun start(size: Int?, direction: CatalogDirection, reset: Boolean, day: SonyCaptureDay? = null,
        autoReadId: Long? = null, sortChanged: Boolean = false): Job {
        require(CatalogBatchSize.valid(size)) { "每批数量须在 1–5000 之间，或选择不限" }
        val owner = source()
        return command {
            if (owner == null || !owner.current()) return@command
            val old = traversal
            val sameSession = old?.source?.generation == owner.generation
            val status = last
            if (!reset && sameSession && status != null && !status.busy && !status.hasMore && status.message == null) return@command
            if (autoReadId != null && (status == null || status.readId != autoReadId || status.busy || status.stopped ||
                    status.message != null || !status.hasMore || !sameSession)) return@command
            val restart = reset || !sameSession || old?.direction != direction
            if (restart) {
                // A complete, unscoped directory can be sorted locally without reading the card again.
                if (sortChanged && sameSession && old?.day == null && status != null && !status.busy && !status.hasMore && status.message == null) {
                    return@command
                }
                worker?.cancelAndJoin()
            } else {
                if (worker?.isActive == true && status?.busy == true) return@command
                worker?.join()
            }
            if (!owner.current()) return@command
            val read = if (restart) Traversal(owner, direction, day, ++revision) else old
            traversal = read
            if (!read.hasMore) return@command
            read.stopping = false
            report(read, busy = true)
            worker = scope.launch {
                try {
                    readBatch(read, size)
                    report(read, busy = false)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { report(read, busy = false, message = error.message ?: "相机目录读取失败") }
            }
        }
    }

    fun pause() = command {
        val read = traversal ?: return@command
        val status = last ?: return@command
        if (status.busy) {
            read.stopping = true
            val stopping = status.copy(stopped = true)
            last = stopping
            if (read.source.current()) progress(stopping)
        }
        worker?.cancelAndJoin()
        if (last?.message == null) report(read, busy = false, stopped = read.hasMore)
    }

    fun discard() = command {
        worker?.cancelAndJoin()
        traversal = null
        last = null
    }

    suspend fun removed(generation: Int, ids: Set<String>?) = commands.withLock {
        val read = traversal?.takeIf { it.source.generation == generation } ?: return@withLock
        worker?.cancelAndJoin()
        if (ids == null) {
            traversal = null
            last = null
            progress(BrowseProgress(generation, 0, 0, false, false, emptySet(), readId = ++revision))
        } else {
            read.loaded.removeAll(ids)
            read.visible = read.loaded.toSet()
            report(read, busy = false, message = last?.message, stopped = last?.stopped == true && read.hasMore)
        }
    }

    private fun command(action: suspend () -> Unit) = scope.launch(start = CoroutineStart.UNDISPATCHED) { commands.withLock { action() } }

    private suspend fun readBatch(read: Traversal, limit: Int?) {
        val directory = read.directory ?: read.source.open(read.direction, read.day).also {
            read.handles.addAll(it.initialHandles)
            read.directory = it
            report(read, busy = true)
        }
        val batchStart = read.cursor
        fun withinLimit(position: Int) = limit == null || position - batchStart < limit
        while (read.hasMore && withinLimit(read.cursor)) {
            val photos = ArrayList<RemotePhoto>(8)
            var position = read.cursor
            try {
                while (photos.size < 8 && withinLimit(position)) {
                    currentCoroutineContext().ensureActive()
                    if (!read.source.current()) throw CancellationException("相机会话已变化")
                    if (position == read.handles.size) {
                        if (!directory.morePages()) break
                        read.handles.addAll(directory.nextPage())
                        continue
                    }
                    read.source.read(read.handles[position])?.let(photos::add)
                    position++
                }
            } finally {
                // Preserve even a partial batch on stop, I/O failure or re-sort. Retry failed storage writes at the old checkpoint.
                if (position > read.cursor && read.source.current()) withContext(NonCancellable) {
                    if (photos.isNotEmpty()) {
                        read.source.publish(photos)
                        photos.forEach { read.loaded += it.asset.id }
                        read.visible = read.loaded.toSet()
                    }
                    read.cursor = position
                    report(read, busy = true)
                }
            }
        }
    }

    private fun report(read: Traversal, busy: Boolean, message: String? = null, stopped: Boolean = false) {
        if (!read.source.current()) return
        val directory = read.directory
        val total = if (directory == null || directory.morePages()) -1 else read.handles.size
        val value = BrowseProgress(read.source.generation, read.cursor, total, busy, read.hasMore, read.visible, message,
            stopped || (busy && read.stopping), read.id, directory?.dates.orEmpty(), read.day)
        last = value
        progress(value)
    }
}
