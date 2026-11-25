package org.prooftray.app.camera

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch

/** Check the event channel when a Sony command stops making progress. Neither timer runs on an idle connection. */
internal class SonyPtpProbe(private val send: () -> Unit, private val failed: (Throwable) -> Unit) : Closeable {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var timer: Job? = null
    private var waiting = false
    private var nextProbeAt = 0L

    @Synchronized fun progress(operation: Int) {
        nextProbeAt = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
        if (timer != null && !waiting) return
        stop()
        timer = scope.launch {
            val owner = coroutineContext.job
            var remainingMillis = 1000L
            while (remainingMillis > 0) {
                delay(remainingMillis)
                synchronized(this@SonyPtpProbe) {
                    if (timer !== owner) return@launch
                    val remaining = nextProbeAt - System.nanoTime()
                    remainingMillis = if (remaining > 0) TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1) else 0L
                    waiting = remainingMillis == 0L
                }
            }
            launch {
                delay(10_000)
                fail(owner, IOException("索尼相机未响应事件通道探测（PTP 0x${operation.toString(16)}）"))
            }
            try { send() } catch (error: IOException) { fail(owner, error) }
        }
    }

    private fun fail(owner: Job, error: Throwable) {
        synchronized(this) {
            if (timer !== owner) return
            stop()
        }
        failed(error)
    }

    @Synchronized fun answered() { if (waiting) stop() }

    @Synchronized fun stop() {
        waiting = false
        timer?.cancel()
        timer = null
    }

    @Synchronized override fun close() { stop(); scope.cancel() }
}
