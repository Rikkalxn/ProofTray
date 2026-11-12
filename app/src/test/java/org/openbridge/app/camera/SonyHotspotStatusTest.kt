package org.openbridge.app.camera

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import org.junit.Assert.*
import org.junit.Test

class SonyHotspotStatusTest {
    @Test fun initialSnapshotsChooseTheCommandByStateRegardlessOfError() {
        for (error in listOf(0, 1, 5, 9)) {
            for (state in 0..2) {
                val status = SonyHotspotStatus()
                status.read(SonyControlData.WifiStatus(state, error), status.revision())
                assertEquals(state != 1, status.beginStart())
                status.checkFailure()
                assertEquals(state == 2, checkNotNull(status.latest()).ready)
                status.close()
            }
        }
    }

    @Test fun initialTerminatingReadOrNotificationFailsBeforeAnyStartCommand() {
        for (notify in listOf(false, true)) {
            for (error in listOf(0, 1, 5)) {
                val status = SonyHotspotStatus()
                val value = SonyControlData.WifiStatus(3, error)
                if (notify) status.notified(value) else status.read(value, status.revision())
                val failure = assertThrows(IOException::class.java) { status.beginStart() }
                assertEquals("机身正在关闭 Wi-Fi，请稍后重试", failure.message)
                status.close()
            }
        }
    }

    @Test fun terminatedNotificationsAfterStartFailForEveryNonzeroError() = runBlocking {
        for (error in listOf(1, 2, 3, 4, 5, 9)) {
            supervisorScope {
                val status = SonyHotspotStatus()
                status.read(SonyControlData.WifiStatus(0, 0), status.revision())
                assertTrue(status.beginStart())
                val entered = CompletableDeferred<Unit>()
                var cancelled = false
                val waiting = async<Unit> {
                    status.guard { try { entered.complete(Unit); awaitCancellation() } finally { cancelled = true } }
                }
                entered.await()
                status.notified(SonyControlData.WifiStatus(0, error))
                try { waiting.await(); fail("Expected startup failure for error $error") }
                catch (failure: IOException) {
                    assertEquals(error == 5, failure.message.orEmpty().contains("存储卡"))
                }
                assertTrue(cancelled)
                status.close()
            }
        }
    }

    @Test fun stoppedWithoutErrorKeepsWaitingAndLaunchedIgnoresError() {
        for (error in listOf(0, 1, 5, 9)) {
            val status = SonyHotspotStatus()
            assertTrue(status.beginStart())
            status.notified(SonyControlData.WifiStatus(0, 0))
            status.checkFailure()
            assertFalse(checkNotNull(status.latest()).ready)
            status.notified(SonyControlData.WifiStatus(1, error))
            status.checkFailure()
            assertFalse(checkNotNull(status.latest()).ready)
            status.notified(SonyControlData.WifiStatus(2, error))
            assertTrue(checkNotNull(status.latest()).ready)
            status.beginInfo()
            status.close()
        }
    }

    @Test fun terminatingNotificationDuringStartupFailsImmediately() {
        val status = SonyHotspotStatus()
        status.beginStart()
        status.notified(SonyControlData.WifiStatus(3, 0))
        val failure = assertThrows(IOException::class.java) { status.checkFailure() }
        assertEquals("机身正在关闭 Wi-Fi，请稍后重试", failure.message)
        status.close()
    }

    @Test fun waitingForAnExistingLaunchStillRejectsTerminationErrors() {
        val status = SonyHotspotStatus()
        status.notified(SonyControlData.WifiStatus(1, 0))
        assertFalse(status.beginStart())
        status.notified(SonyControlData.WifiStatus(0, 1))
        assertThrows(IOException::class.java) { status.checkFailure() }
        status.close()
    }

    @Test fun delayedReadsCannotOverwriteNewerStatusNotifications() {
        val status = SonyHotspotStatus()
        val initialRead = status.revision()
        status.notified(SonyControlData.WifiStatus(2, 0))
        status.read(SonyControlData.WifiStatus(0, 0), initialRead)
        assertTrue(checkNotNull(status.latest()).ready)
        status.close()
    }

    @Test fun terminationWhileReadingCredentialsCancelsTheRead() = runBlocking {
        supervisorScope {
            val status = SonyHotspotStatus()
            status.notified(SonyControlData.WifiStatus(2, 0))
            status.beginInfo()
            val entered = CompletableDeferred<Unit>()
            var cancelled = false
            val read = async<Unit> {
                status.guard { try { entered.complete(Unit); awaitCancellation() } finally { cancelled = true } }
            }
            entered.await()
            status.notified(SonyControlData.WifiStatus(0, 5))
            try { read.await(); fail("Expected hotspot failure") }
            catch (error: IOException) { assertTrue(error.message.orEmpty().contains("存储卡")) }
            assertTrue(cancelled)
            status.close()
        }
    }

    @Test fun terminationInTheGapBeforeCredentialsIsNotLost() {
        val status = SonyHotspotStatus()
        status.notified(SonyControlData.WifiStatus(2, 0))
        status.notified(SonyControlData.WifiStatus(0, 0))
        assertThrows(IOException::class.java) { status.beginInfo() }
        status.close()
    }

    @Test fun anOldTerminatedSnapshotDoesNotPreventStartingWifi() = runBlocking {
        val status = SonyHotspotStatus()
        status.notified(SonyControlData.WifiStatus(0, 5))
        status.checkFailure()
        status.notified(SonyControlData.WifiStatus(1, 1))
        assertTrue(checkNotNull(status.latest()).launching)
        status.notified(SonyControlData.WifiStatus(2, 0))
        status.beginInfo()
        assertEquals("credentials", status.guard { "credentials" })
        status.close()
        status.notified(SonyControlData.WifiStatus(0, 0))
        status.checkFailure() // A retired monitor cannot turn a completed exchange into failure.
    }

    @Test fun cancellingTheCallerStopsItsPendingReads() = runBlocking {
        val status = SonyHotspotStatus()
        val entered = CompletableDeferred<Unit>()
        var cancelled = false
        val read = async<Unit> { status.guard { try { entered.complete(Unit); awaitCancellation() } finally { cancelled = true } } }
        entered.await()
        read.cancelAndJoin()
        assertTrue(cancelled)
        status.close()
    }
}
