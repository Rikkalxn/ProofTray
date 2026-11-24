package org.prooftray.app.camera

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SonyLocationSessionTest {
    private class Transport : SonyLocationTransport {
        override var live = true
        val operations = mutableListOf<String>()
        var callback: ((ByteArray) -> Unit)? = null
        var failId: Int? = null
        var beforeLocation: () -> Unit = {}
        override suspend fun read(id: Int): ByteArray {
            operations += "read ${id.toString(16)}"
            return if (id == SonyLocationProtocol.CAPABILITIES) byteArrayOf(0, 0, 0, 0, 2) else byteArrayOf(0)
        }
        override suspend fun write(id: Int, value: ByteArray, allowed: () -> Boolean) {
            if (id == SonyLocationProtocol.LOCATION) beforeLocation()
            check(allowed()) { "stale" }
            check(id != failId) { "rejected" }
            operations += "write ${id.toString(16)} ${if (value.size == 1) value[0] else value.size}"
        }
        override suspend fun observe(callback: ((ByteArray) -> Unit)?) {
            this.callback = callback
            operations += if (callback == null) "unsubscribe" else "subscribe"
        }
        override fun close() { live = false }
    }

    @Test fun sharesLocationOnlyAfterLockAndPreservesCameraClockSettings() = runBlocking {
        val transport = Transport()
        val session = SonyLocationSession(transport, { 2_000_000_000L }, {})
        session.prepare(false)
        session.send(CameraLocationFix(0.0, 0.0, Double.NaN, 1_700_000_000_000L, 1_000_000_000L)) { true }
        session.stop()
        assertEquals(listOf("subscribe", "write dd30 1", "write dd31 1", "read dd32", "read dd33", "read dd21",
            "write dd11 95", "write dd31 0", "write dd30 0", "unsubscribe"), transport.operations)
        assertTrue(transport.live)
        assertFalse(session.ready)
    }

    @Test fun rejectedLockNeverDisablesOrUnlocksAnotherDevice() = runBlocking {
        val transport = Transport().apply { failId = SonyLocationProtocol.LOCK }
        val session = SonyLocationSession(transport, { 0L }, {})
        assertTrue(runCatching { session.prepare(false) }.isFailure)
        session.stop()
        assertEquals(listOf("subscribe", "unsubscribe"), transport.operations)
        assertTrue(transport.live)
    }

    @Test fun cameraPauseReleasesOurLockAndWaitsForExplicitResume() = runBlocking {
        val transport = Transport()
        var changes = 0
        val session = SonyLocationSession(transport, { 0L }) { changes++ }
        session.prepare(false)
        transport.operations.clear()
        transport.callback?.invoke(byteArrayOf(3, 1, 2, 0))
        assertEquals(1, changes)
        assertFalse(session.ready)
        session.stop()
        session.prepare(false)
        assertEquals(listOf("write dd30 0", "unsubscribe"), transport.operations)
        session.prepare(true)
        assertTrue(session.ready)
        assertFalse(session.paused)
    }

    @Test fun queuedLocationIsRejectedIfItExpiresOrItsTargetChanges() = runBlocking {
        val transport = Transport()
        var now = 2_000_000_000L
        val session = SonyLocationSession(transport, { now }, {})
        val fix = CameraLocationFix(0.0, 0.0, Double.NaN, 1_700_000_000_000L, 1_000_000_000L)
        session.prepare(false)
        transport.operations.clear()
        transport.beforeLocation = { now = 11_000_000_000L }
        assertTrue(runCatching { session.send(fix) { true } }.isFailure)
        now = 2_000_000_000L
        transport.beforeLocation = {}
        assertTrue(runCatching { session.send(fix) { false } }.isFailure)
        assertTrue(transport.operations.isEmpty())
    }

    @Test fun failedUnlockClosesTheAmbiguousSession() = runBlocking {
        val transport = Transport()
        val session = SonyLocationSession(transport, { 0L }, {})
        session.prepare(false)
        transport.failId = SonyLocationProtocol.LOCK
        assertTrue(runCatching { session.stop() }.isFailure)
        assertFalse(transport.live)
        assertFalse(session.ready)
    }
}
