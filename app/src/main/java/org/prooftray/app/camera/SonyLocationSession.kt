package org.prooftray.app.camera

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

internal interface SonyLocationTransport {
    val live: Boolean
    suspend fun read(id: Int): ByteArray
    suspend fun write(id: Int, value: ByteArray, allowed: () -> Boolean = { true })
    suspend fun observe(callback: ((ByteArray) -> Unit)?)
    fun close()
}

/** Only releases a lock acknowledged on this connection. Camera-side pauses require an explicit resume. */
internal class SonyLocationSession(
    private val transport: SonyLocationTransport, private val nowNanos: () -> Long, private val changed: () -> Unit
) {
    private val operations = Mutex()
    private var ownsLock = false
    private var subscribed = false
    private var includeTimeZone = false
    @Volatile private var listening = false
    @Volatile private var configured = false
    @Volatile var paused = false
        private set
    val ready: Boolean get() = configured && !paused && transport.live

    suspend fun prepare(resume: Boolean) = operations.withLock {
        check(transport.live) { "索尼蓝牙连接已结束" }
        if (paused && !resume) return@withLock
        if (ready) return@withLock
        if (ownsLock || subscribed) stopLocked()
        if (resume) paused = false
        try {
            val started = withTimeoutOrNull(30_000) {
                subscribed = true
                listening = true
                transport.observe { value ->
                    if (listening && SonyLocationProtocol.disabled(value)) {
                        paused = true
                        configured = false
                        changed()
                    }
                }
                transport.write(SonyLocationProtocol.LOCK, byteArrayOf(1)) { !paused }
                ownsLock = true
                transport.write(SonyLocationProtocol.ENABLE, byteArrayOf(1)) { !paused }
                // Preserve the camera's automatic clock and area correction preferences.
                transport.read(SonyLocationProtocol.TIME_SETTING)
                transport.read(SonyLocationProtocol.AREA_SETTING)
                includeTimeZone = SonyLocationProtocol.supportsTimeZone(transport.read(SonyLocationProtocol.CAPABILITIES))
                check(!paused && transport.live) { "机身已暂停位置关联，请在机身确认后点「恢复同步」" }
                true
            }
            check(started == true) { "索尼位置关联准备超时，请确认机身后重试" }
            configured = true
        } catch (error: Exception) {
            withContext(NonCancellable) { runCatching { stopLocked() } }
            throw error
        }
    }

    suspend fun send(fix: CameraLocationFix, allowed: () -> Boolean) = operations.withLock {
        check(ready) { "机身位置关联尚未就绪" }
        check(fix.fresh(nowNanos(), SonyLocationProtocol.MAX_AGE_NANOS)) { "定位已过期，等待新的位置" }
        val packet = SonyLocationProtocol.encode(fix, includeTimeZone)
        transport.write(SonyLocationProtocol.LOCATION, packet) {
            ready && allowed() && fix.fresh(nowNanos(), SonyLocationProtocol.MAX_AGE_NANOS)
        }
        check(ready && allowed()) { "位置同步时相机会话已变化" }
    }

    suspend fun stop() = withContext(NonCancellable) { operations.withLock { stopLocked() } }

    private suspend fun stopLocked() {
        configured = false
        listening = false
        try {
            if (ownsLock && transport.live) {
                if (!paused) transport.write(SonyLocationProtocol.ENABLE, byteArrayOf(0))
                transport.write(SonyLocationProtocol.LOCK, byteArrayOf(0))
            }
            if (subscribed && transport.live) transport.observe(null)
        } catch (error: Exception) {
            // A failed cleanup must not leave a live connection holding an ambiguous lease.
            transport.close()
            throw error
        } finally {
            ownsLock = false
            subscribed = false
        }
    }
}
