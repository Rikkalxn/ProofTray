package org.openbridge.app.camera

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class GattReleaseTest {
    @Test fun queuedDisconnectHasAnOwnerDeadlineAndClosesBeforeReleasingWaiters() = runBlocking {
        var expired: (() -> Unit)? = null
        var callback: (() -> Unit)? = null
        var closes = 0
        var timerCancelled = false
        lateinit var release: GattRelease
        release = GattRelease({ assertFalse(release.closed.isCompleted); closes++ }) {
            expired = it; Closeable { timerCancelled = true }
        }
        release.begin { callback = it } // Submitted, but the BLE request never starts.
        assertFalse(release.closed.isCompleted)
        checkNotNull(expired).invoke()
        release.closed.await()
        assertEquals(1, closes)
        assertTrue(timerCancelled)
        checkNotNull(callback).invoke() // A late old callback cannot close or release twice.
        assertEquals(1, closes)
    }

    @Test fun normalAndReentrantCallbacksCloseOnlyThisOwnerOnce() = runBlocking {
        var callback: (() -> Unit)? = null
        var closes = 0
        val release = GattRelease({ closes++; callback?.invoke() }) { Closeable {} }
        release.begin { callback = it; it() }
        release.closed.await()
        release.begin { fail("Already retired") }
        assertEquals(1, closes)
    }

    @Test fun cancellingOneWaiterDoesNotCancelTheOwnersClose() = runBlocking {
        var expired: (() -> Unit)? = null
        var closed = false
        val release = GattRelease({ closed = true }) { expired = it; Closeable {} }
        release.begin { }
        val waiter = async { release.closed.await() }
        waiter.cancelAndJoin()
        assertFalse(release.closed.isCancelled)
        checkNotNull(expired).invoke()
        release.closed.await()
        assertTrue(closed)
    }

    @Test fun closeFailureIsStillReportedByTheOriginalBarrier() {
        val release = GattRelease({ throw IOException("close failed") }) { Closeable {} }
        release.begin { it() }
        assertThrows(IOException::class.java) { runBlocking { release.closed.await() } }
    }

    @Test fun failedCloseIsRetriedBeforeTheNextConnectionAndReportedOnlyOnce() = runBlocking {
        val events = mutableListOf<String>()
        var closes = 0
        val release = GattRelease({
            events += "close ${++closes}"
            if (closes == 1) throw IOException("close failed")
        }) { Closeable {} }
        release.begin { it() }
        var previous: GattRelease? = release
        val retiring = previous
        retiring?.awaitClosed { events += "reported" }
        synchronized(events) { if (previous === retiring) previous = null }
        events += "connect"
        assertNull(previous)
        assertEquals(listOf("close 1", "close 2", "reported", "connect"), events)
        release.awaitClosed { fail("Recovery was already reported") }
        assertEquals(2, closes)
    }

    @Test fun aSecondCloseFailureDoesNotPoisonSubsequentConnectionAttempts() = runBlocking {
        var closes = 0
        var reports = 0
        val release = GattRelease({ closes++; throw IOException("close $closes failed") }) { Closeable {} }
        release.begin { it() }
        repeat(2) { release.awaitClosed { reports++; assertEquals(1, it.suppressed.size) } }
        assertEquals(2, closes)
        assertEquals(1, reports)
    }

    @Test fun recoveryWaitersCannotBypassAPendingCloseOrRecoverAfterCancellation() = runBlocking {
        var finish: (() -> Unit)? = null
        var closes = 0
        var opened = false
        val release = GattRelease({ closes++ }) { Closeable {} }
        release.begin { finish = it }
        val waiting = async(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            release.awaitClosed { fail("Cancellation is not a close failure") }
            opened = true
        }
        assertFalse(opened)
        waiting.cancelAndJoin()
        assertEquals(0, closes)
        checkNotNull(finish).invoke()
        release.awaitClosed { fail("Close succeeded") }
        assertEquals(1, closes)
    }

    @Test fun failedDisconnectSubmissionStillClosesTheOldTransport() = runBlocking {
        var closed = false
        val release = GattRelease({ closed = true }) { Closeable {} }
        release.begin { throw IllegalStateException("submission rejected") }
        release.closed.await()
        assertTrue(closed)
    }
}
