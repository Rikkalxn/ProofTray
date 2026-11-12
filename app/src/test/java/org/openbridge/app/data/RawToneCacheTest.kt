package org.openbridge.app.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RawToneCacheTest {
    private val key = RawToneKey("photo", "content:photo", 123, "mtime:1")
    private val profile = RawToneProfile(List(257) { it / 256f }, RawToneOrigin.CAMERA_PREVIEW)

    @Test fun previewAndExportWaitForOneSharedRecipe() = runBlocking {
        val cache = RawToneCache()
        val gate = CompletableDeferred<Unit>()
        var builds = 0
        var writes = 0
        val preview = async(start = CoroutineStart.UNDISPATCHED) {
            cache.resolve(key, { null }, { _, _ -> writes++ }) { builds++; gate.await(); profile }
        }
        val export = async(start = CoroutineStart.UNDISPATCHED) {
            cache.resolve(key, { null }, { _, _ -> writes++ }) { builds++; profile }
        }
        gate.complete(Unit)
        assertSame(preview.await(), export.await())
        assertEquals(1, builds)
        assertEquals(1, writes)
    }

    @Test fun fileOrInputProfileChangesRequireANewRecipe() = runBlocking {
        val cache = RawToneCache()
        var builds = 0
        for (current in listOf(key, key, key.copy(size = 124), key.copy(revision = "mtime:2"), key.copy(inputProfile = "future-dcp"))) {
            cache.resolve(current, { null }, { _, _ -> }) { builds++; profile }
        }
        assertEquals(4, builds)
    }

    @Test fun cancellationDoesNotPublishAnIncompleteRecipe() = runBlocking {
        val cache = RawToneCache()
        var writes = 0
        val building = async(start = CoroutineStart.UNDISPATCHED) {
            cache.resolve(key, { null }, { _, _ -> writes++ }) { awaitCancellation() }
        }
        building.cancelAndJoin()
        assertEquals(0, writes)
        assertSame(profile, cache.resolve(key, { null }, { _, _ -> writes++ }) { profile })
        assertEquals(1, writes)
    }

    @Test fun absentProviderRevisionAndTransientFailuresAreNotPersisted() = runBlocking {
        val cache = RawToneCache()
        var builds = 0
        repeat(2) {
            cache.resolve(null, { error("No stable key") }, { _, _ -> error("No stable key") }) { builds++; profile }
            cache.resolve(key, { null }, { _, _ -> error("Transient failure") }) {
                builds++; RawToneProfile.generic(RawToneFallback.UNAVAILABLE)
            }
        }
        assertEquals(4, builds)
    }
}
