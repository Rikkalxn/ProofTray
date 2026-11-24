package org.prooftray.app.camera

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import org.prooftray.app.domain.AppSettings

class NikonControlPointTest {
    @Test fun requestNibblesAreIndependentAndReservedBitsAreNotSerialized() {
        val control = checkNotNull(NikonControlPoint.decode(byteArrayOf(0x21, 0xf1.toByte())))
        assertTrue(control.timeRequested)
        assertTrue(control.locationRequested)
        assertTrue(control.connectionRequested)
        assertArrayEquals(byteArrayOf(0x21, 1), control.encode())
        assertFalse(control.copy(time = 2).timeRequested)
        assertTrue(control.copy(location = 1).locationRequested)
        assertFalse(control.copy(location = 3, connection = 2).locationRequested)
        assertFalse(control.copy(connection = 2).connectionRequested)
        assertNull(NikonControlPoint.decode(byteArrayOf()))
        assertNull(NikonControlPoint.decode(byteArrayOf(1)))
    }

    @Test fun acknowledgementsClearOnlyTheRequestedFieldAndReservedBits() {
        val control = checkNotNull(NikonControlPoint.decode(byteArrayOf(0x21, 0xf1.toByte())))
        assertArrayEquals(byteArrayOf(0x20, 1), control.copy(time = 0).encode())
        assertArrayEquals(byteArrayOf(0x21, 0), control.copy(connection = 0).encode())
    }

    @Test fun writesTimeBeforeReadingAndAcknowledgesTheFreshControlValue() = runBlocking {
        val session = ControlledSession()
        assertTrue(session.synchronize())
        assertEquals(listOf("write 2006", "read 2008", "write 2008"), session.events)
        assertArrayEquals(byteArrayOf(1, 2, 3), session.writes.first().second)
        assertArrayEquals(byteArrayOf(0x20, 1), session.writes.last().second)
    }

    @Test fun aRequestAlreadyClearedByTheCameraDoesNotNeedAnotherWrite() = runBlocking {
        val session = ControlledSession().apply { control = byteArrayOf(0x20, 0xf1.toByte()) }
        assertTrue(session.synchronize())
        assertEquals(listOf("write 2006", "read 2008"), session.events)
    }

    @Test fun anExpiredSessionWaitingForAdmissionNeverWrites() = runBlocking {
        val session = ControlledSession()
        session.operation.lock()
        val queued = async(start = CoroutineStart.UNDISPATCHED) { session.synchronize() }
        session.generation++
        session.operation.unlock()
        assertFalse(queued.await())
        assertTrue(session.events.isEmpty())
    }

    @Test fun replacementDuringTimeWriteDoesNotReadOrAcknowledgeTheNewSession() = runBlocking {
        val session = ControlledSession()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        session.afterTimeWrite = { entered.complete(Unit); resume.await() }
        val syncing = async { session.synchronize() }
        entered.await()
        session.generation++
        resume.complete(Unit)
        assertFalse(syncing.await())
        assertEquals(listOf("write 2006"), session.events)
    }

    @Test fun replacementDuringControlReadDoesNotWriteAnAcknowledgement() = runBlocking {
        val session = ControlledSession()
        val entered = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        session.duringRead = { entered.complete(Unit); resume.await() }
        val syncing = async { session.synchronize() }
        entered.await()
        session.generation++
        resume.complete(Unit)
        assertFalse(syncing.await())
        assertEquals(listOf("write 2006", "read 2008"), session.events)
    }

    @Test fun failedTimeWriteNeverClearsTheRequest() {
        val session = ControlledSession().apply { afterTimeWrite = { throw IOException("write rejected") } }
        assertThrows(IOException::class.java) { runBlocking { session.synchronize() } }
        assertEquals(listOf("write 2006"), session.events)
    }

    @Test fun pairingAndWifiDeferTheRequestUntilDataIsReady() = runBlocking {
        val session = ControlledSession()
        var pairing = true
        var wifi = false
        var enabled = true
        session.allowed = { enabled && !wifi && bluetoothDataReady(true, true, pairing) }
        assertFalse(session.synchronize())
        pairing = false
        wifi = true
        assertFalse(session.synchronize())
        wifi = false
        enabled = false
        assertFalse(session.synchronize())
        assertTrue(session.events.isEmpty())
        enabled = true
        assertTrue(session.synchronize())
        assertEquals(listOf("write 2006", "read 2008", "write 2008"), session.events)
        assertTrue(AppSettings().requestedTimeSync)
        assertFalse(AppSettings().pairingTimeSync)
    }

    private class ControlledSession {
        val operation = Mutex()
        var generation = 1
        var control = byteArrayOf(0x21, 0xf1.toByte())
        var allowed: () -> Boolean = { true }
        var afterTimeWrite: suspend () -> Unit = {}
        var duringRead: suspend () -> Unit = {}
        val events = mutableListOf<String>()
        val writes = mutableListOf<Pair<String, ByteArray>>()

        suspend fun synchronize(): Boolean {
            val expected = generation
            return operation.withLock {
                syncNikonRequestedTime({ byteArrayOf(1, 2, 3) }, { id ->
                    events += "read $id"
                    duringRead()
                    control
                }, { id, value ->
                    events += "write $id"
                    writes += id to value
                    if (id == "2006") afterTimeWrite()
                }) { generation == expected && allowed() }
            }
        }
    }
}
