package org.openbridge.app.camera

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only the retiring owner releases its barrier, after its own transport is closed. The deadline includes queue waiting. */
internal class GattRelease(private val closeTransport: () -> Unit, private val schedule: (() -> Unit) -> Closeable) {
    private val completion = CompletableDeferred<Unit>()
    val closed: Deferred<Unit> get() = completion
    private val started = AtomicBoolean(false)
    private val closing = AtomicBoolean(false)
    private val deadline = AtomicReference<Closeable?>()
    private val recovery = Mutex()
    private var recovered = false

    suspend fun awaitClosed(reportFailure: (Exception) -> Unit) {
        try { closed.await() }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            recovery.withLock {
                if (recovered) return@withLock
                // The failed owner has finished closing. Retry that same transport, never bypass a pending close.
                try { closeTransport() }
                catch (retry: Exception) { if (retry !== error) error.addSuppressed(retry) }
                reportFailure(error)
                recovered = true
            }
        }
    }

    fun begin(disconnect: (finished: () -> Unit) -> Unit) {
        if (!started.compareAndSet(false, true)) return
        try {
            val timer = schedule(::finish)
            deadline.set(timer)
            if (completion.isCompleted) cancelDeadline()
            if (!closing.get()) disconnect(::finish)
        } catch (_: Exception) { finish() }
    }

    private fun finish() {
        if (!closing.compareAndSet(false, true)) return
        try {
            closeTransport()
            completion.complete(Unit)
        } catch (error: Exception) { completion.completeExceptionally(error) }
        finally { cancelDeadline() }
    }

    private fun cancelDeadline() { try { deadline.getAndSet(null)?.close() } catch (_: Exception) { /* Transport closure owns the result. */ } }
}
