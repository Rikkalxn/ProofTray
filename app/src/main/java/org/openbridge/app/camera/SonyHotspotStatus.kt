package org.openbridge.app.camera

import java.io.Closeable
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** Owns one hotspot exchange, including the interval between Launched and the last credentials read. */
internal class SonyHotspotStatus : Closeable {
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val failure = CompletableDeferred<Exception>()
    private var version = 0L
    private var current: SonyControlData.WifiStatus? = null
    private var problem: Exception? = null
    private var starting = false
    private var readingInfo = false
    private var closed = false

    @Synchronized fun revision(): Long = version
    @Synchronized fun latest(): SonyControlData.WifiStatus? = current

    @Synchronized fun notified(value: SonyControlData.WifiStatus) {
        if (closed) return
        version++
        current = value
        checkTermination(value)
        changes.trySend(Unit)
    }

    @Synchronized fun read(value: SonyControlData.WifiStatus?, beforeRead: Long) {
        // A slow read response cannot overwrite a newer notification, in particular a hotspot termination.
        if (closed || beforeRead != version || value == null) return
        version++
        current = value
        checkTermination(value)
    }

    @Synchronized fun beginInfo() {
        readingInfo = true
        current?.let(::checkTermination)
        checkFailure()
    }

    @Synchronized fun beginStart(): Boolean {
        checkFailure()
        discardChanges()
        starting = true
        return current?.launching != true
    }

    @Synchronized fun failed(error: Exception) {
        if (closed || problem != null) return
        problem = error
        failure.complete(error)
        changes.trySend(Unit)
    }

    private fun checkTermination(value: SonyControlData.WifiStatus) {
        if (readingInfo) {
            if (value.terminated) failed(IOException(if (value.error == 5) "索尼相机没有可读取的存储卡" else "机身已停止 Wi-Fi，请重新连接"))
        } else if (value.terminating) {
            failed(IOException("机身正在关闭 Wi-Fi，请稍后重试"))
        } else if (starting && value.terminated && value.error != 0) {
            failed(IOException(if (value.error == 5) "索尼相机没有可读取的存储卡" else "机身未能开启 Wi-Fi（状态 ${value.error}）"))
        }
    }

    @Synchronized fun checkFailure() { problem?.let { throw it } }
    fun discardChanges() { while (changes.tryReceive().isSuccess) { /* Discard only the pre-command snapshot. */ } }
    suspend fun changed(): Boolean = withTimeoutOrNull(1500) { changes.receive(); true } == true

    suspend fun <T> guard(read: suspend () -> T): T = coroutineScope {
        checkFailure()
        val operation = async { read() }
        try {
            select<T> { failure.onAwait { throw it }; operation.onAwait { it } }.also { checkFailure() }
        } finally { operation.cancel() }
    }

    @Synchronized override fun close() { closed = true; changes.close() }
}
