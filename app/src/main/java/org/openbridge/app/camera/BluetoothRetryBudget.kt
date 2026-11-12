package org.openbridge.app.camera

/** Kept across BLE collector restarts; reconnecting the control link is not a successful photo transfer. */
internal class BluetoothRetryBudget {
    data class State(val failures: Int = 0, val retryAt: Long = 0, val blockedReason: String? = null) {
        val exhausted: Boolean get() = failures >= 3 || blockedReason != null
        fun delayMillis(now: Long): Long = (retryAt - now).coerceAtLeast(0)
    }
    private var current = State()

    @Synchronized fun state(): State = current
    @Synchronized fun reset() { current = State() }
    @Synchronized fun block(reason: String) { current = current.copy(blockedReason = reason) }
    @Synchronized fun failed(now: Long, baseDelayMillis: Long): State {
        val failures = (current.failures + 1).coerceAtMost(3)
        val delay = (baseDelayMillis.coerceIn(1000, 120000) * (1L shl (failures - 1))).coerceAtMost(120000)
        current = State(failures, now + delay)
        return current
    }
}
