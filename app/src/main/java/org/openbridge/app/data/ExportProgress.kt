package org.openbridge.app.data

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive

/** Narrow contract for an executing export; it cannot change the queue or publish a file. */
internal class ExportProgress(
    private val coroutine: CoroutineContext, private val cancelled: () -> Boolean,
    private val onProgress: suspend (Float?, String) -> Unit
) {
    var phase: String = "准备导出"

    fun ensureActive() {
        coroutine.ensureActive()
        if (cancelled()) throw CancellationException("Cancelled")
    }

    suspend fun report(fraction: Float?, message: String) {
        ensureActive()
        onProgress(fraction, message)
    }
}
