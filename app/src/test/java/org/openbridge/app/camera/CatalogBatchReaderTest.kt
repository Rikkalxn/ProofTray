package org.openbridge.app.camera

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.openbridge.app.domain.DownloadState
import org.openbridge.app.domain.MediaFormat
import org.openbridge.app.domain.PhotoAsset
import org.openbridge.app.domain.SortOrder

class CatalogBatchReaderTest {
    private fun photo(handle: Long): RemotePhoto {
        val asset = PhotoAsset(handle.toString(), "sample:$handle", "$handle.jpg", MediaFormat.JPEG, downloadState = DownloadState.REMOTE)
        return RemotePhoto(asset, handle, 1, "camera", "")
    }

    private suspend fun Channel<BrowseProgress>.awaitStatus(predicate: (BrowseProgress) -> Boolean): BrowseProgress = withTimeout(5000) {
        var result = receive()
        while (!predicate(result)) result = receive()
        result
    }

    @Test fun customLimitsAndSavedIntegerPreferencesUseTheSameValidation() {
        for (size in listOf(25, 50, 100, 200, 1, 5000)) {
            assertTrue(CatalogBatchSize.valid(size))
            assertEquals(size, CatalogBatchSize.fromPreference(size))
        }
        for (size in listOf(0, 5001, -1, -7)) {
            assertFalse(CatalogBatchSize.valid(size))
            assertEquals(50, CatalogBatchSize.fromPreference(size))
        }
        assertEquals(50, CatalogBatchSize.fromPreference(null))
        assertEquals(50, CatalogBatchSize.fromPreference("bad"))
        assertTrue(CatalogBatchSize.valid(null))
        assertNull(CatalogBatchSize.fromPreference(CatalogBatchSize.UNLIMITED_PREF))
    }

    @Test fun orderingUsesCameraSequencePerCardAndDefaultsToNewestForNonTimeSorts() {
        val original = longArrayOf(31, 12, 20)
        for (sort in SortOrder.entries) {
            val expected = if (sort == SortOrder.OLDEST) listOf(31L, 12L, 20L) else listOf(20L, 12L, 31L)
            assertEquals(sort.name, expected, orderedMtpHandles(original, sort.catalogDirection()))
        }
    }

    @Test fun changingSortCancelsAndJoinsBeforeReadingFromTheOtherEnd() = runBlocking {
        val events = Channel<BrowseProgress>(Channel.UNLIMITED)
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val orders = mutableListOf<CatalogDirection>()
        val writes = mutableListOf<List<Long>>()
        var waitOnce = true
        val source = CatalogReadSource(1, { true }, { direction, _ ->
            if (orders.isNotEmpty()) assertTrue(cancelled.isCompleted)
            orders += direction
            CatalogDirectory(orderedMtpHandles(longArrayOf(1, 2, 3, 4, 5), direction))
        }, { handle ->
            if (handle == 4L && waitOnce) {
                waitOnce = false
                entered.complete(Unit)
                try { awaitCancellation() } finally { cancelled.complete(Unit) }
            }
            photo(handle)
        }, { writes += it.map(RemotePhoto::handle) })
        val reader = CatalogBatchReader(this, { source }) { events.trySend(it) }
        reader.start(null, CatalogDirection.NEWEST, reset = true)
        withTimeout(5000) { entered.await() }
        reader.start(2, CatalogDirection.OLDEST, reset = true, sortChanged = true).join()
        val done = events.awaitStatus { it.readId == 2L && !it.busy }
        assertEquals(listOf(CatalogDirection.NEWEST, CatalogDirection.OLDEST), orders)
        assertEquals(listOf(listOf(5L), listOf(1L, 2L)), writes)
        assertEquals(setOf("1", "2"), done.photoIds)
        assertTrue(done.hasMore)
    }

    @Test fun stopCommitsThePartialGroupAndManualResumeUsesThatCheckpoint() = runBlocking {
        val events = Channel<BrowseProgress>(Channel.UNLIMITED)
        val entered = CompletableDeferred<Unit>()
        val writes = mutableListOf<List<Long>>()
        val reads = mutableListOf<Long>()
        var opens = 0
        val source = CatalogReadSource(1, { true }, { _, _ -> opens++; CatalogDirectory((1L..14L).toList()) }, { handle ->
            reads += handle
            if (handle == 11L && entered.complete(Unit)) awaitCancellation()
            photo(handle)
        }, { writes += it.map(RemotePhoto::handle) })
        val reader = CatalogBatchReader(this, { source }) { events.trySend(it) }
        reader.start(null, CatalogDirection.OLDEST, reset = true)
        withTimeout(5000) { entered.await() }
        reader.pause().join()
        val paused = events.awaitStatus { it.stopped && !it.busy }
        assertEquals(10, paused.indexed)
        assertTrue(paused.hasMore)
        assertEquals(listOf(8, 2), writes.map { it.size })
        reader.start(null, CatalogDirection.OLDEST, reset = false, autoReadId = paused.readId).join()
        assertEquals(11, reads.size)
        reader.start(null, CatalogDirection.OLDEST, reset = false)
        val done = events.awaitStatus { !it.busy && !it.hasMore }
        assertEquals(1, opens)
        assertEquals(paused.readId, done.readId)
        assertEquals((1L..14L).toList(), writes.flatten())
        assertEquals((1L..11L).toList() + (11L..14L), reads)
    }

    @Test fun oneBatchCanSpanPagesAndUnlimitedPublishesEightAtATime() = runBlocking {
        val events = Channel<BrowseProgress>(Channel.UNLIMITED)
        val writes = mutableListOf<List<Long>>()
        val pages = ArrayDeque(listOf((1L..100L).toList(), emptyList(), (101L..200L).toList(), (201L..217L).toList()))
        val source = CatalogReadSource(1, { true }, { _, _ ->
            CatalogDirectory(morePages = { pages.isNotEmpty() }) { pages.removeFirst() }
        }, { photo(it) }, { writes += it.map(RemotePhoto::handle) })
        val reader = CatalogBatchReader(this, { source }) { events.trySend(it) }
        reader.start(205, CatalogDirection.NEWEST, reset = true)
        assertEquals(205, events.awaitStatus { !it.busy }.indexed)
        assertEquals(List(25) { 8 } + 5, writes.map { it.size })
        reader.start(null, CatalogDirection.NEWEST, reset = false)
        val end = events.awaitStatus { !it.busy && !it.hasMore }
        assertEquals(217, end.total)
        assertEquals(listOf(8, 4), writes.takeLast(2).map { it.size })
        assertEquals((1L..217L).toList(), writes.flatten())
    }

    @Test fun failureKeepsPublishedPhotosAndOnlyManualRetryReadsTheFailedObject() = runBlocking {
        val events = Channel<BrowseProgress>(Channel.UNLIMITED)
        val calls = mutableListOf<Long>()
        var fail = true
        val source = CatalogReadSource(1, { true }, { _, _ -> CatalogDirectory(listOf(1, 2, 3)) }, { handle ->
            calls += handle
            if (handle == 2L && fail) { fail = false; throw IOException("card busy") }
            photo(handle)
        }, {})
        val reader = CatalogBatchReader(this, { source }) { events.trySend(it) }
        reader.start(null, CatalogDirection.NEWEST, reset = true)
        val error = events.awaitStatus { it.message != null }
        assertEquals(setOf("1"), error.photoIds)
        assertTrue(error.hasMore)
        reader.start(null, CatalogDirection.NEWEST, reset = false, autoReadId = error.readId).join()
        assertEquals(listOf(1L, 2L), calls)
        reader.start(null, CatalogDirection.NEWEST, reset = false)
        assertEquals(3, events.awaitStatus { !it.busy && !it.hasMore }.indexed)
        assertEquals(listOf(1L, 2L, 2L, 3L), calls)
    }

    @Test fun enumerationFailureCanRetryBeforeTheFirstHandleExists() = runBlocking {
        val events = Channel<BrowseProgress>(Channel.UNLIMITED)
        var opens = 0
        val source = CatalogReadSource(1, { true }, { _, _ ->
            if (++opens == 1) throw IOException("card not ready")
            CatalogDirectory(listOf(4L))
        }, { photo(it) }, {})
        val reader = CatalogBatchReader(this, { source }) { events.trySend(it) }
        reader.start(25, CatalogDirection.NEWEST, reset = true)
        val failed = events.awaitStatus { it.message != null }
        assertTrue(failed.hasMore)
        assertEquals(0, failed.indexed)
        reader.start(25, CatalogDirection.NEWEST, reset = false, autoReadId = failed.readId).join()
        assertEquals(1, opens)
        reader.start(25, CatalogDirection.NEWEST, reset = false)
        assertEquals(setOf("4"), events.awaitStatus { !it.busy && !it.hasMore }.photoIds)
    }

    @Test fun sortWithTheSameReadDirectionRestartsPartialButReusesCompleteDirectory() = runBlocking {
        val events = Channel<BrowseProgress>(Channel.UNLIMITED)
        var opens = 0
        val source = CatalogReadSource(1, { true }, { _, _ -> opens++; CatalogDirectory(listOf(3L, 2L, 1L)) }, { photo(it) }, {})
        val reader = CatalogBatchReader(this, { source }) { events.trySend(it) }
        reader.start(1, SortOrder.NEWEST.catalogDirection(), reset = true)
        val partial = events.awaitStatus { !it.busy }
        reader.start(null, SortOrder.NAME.catalogDirection(), reset = true, sortChanged = true)
        val complete = events.awaitStatus { !it.busy && !it.hasMore }
        assertNotEquals(partial.readId, complete.readId)
        reader.start(1, SortOrder.OLDEST.catalogDirection(), reset = true, sortChanged = true).join()
        assertEquals(2, opens)
        assertTrue(events.tryReceive().isFailure)
    }
}
