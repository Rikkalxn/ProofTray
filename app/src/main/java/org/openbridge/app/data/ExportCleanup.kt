package org.openbridge.app.data

internal enum class OutputState { ABSENT, PENDING, PUBLISHED, UNKNOWN }

/** The caller's delete must also require IS_PENDING = 1, as the row can change after inspection. */
internal fun cleanPendingMedia(inspect: () -> OutputState, deletePending: () -> Int): OutputState {
    return try {
        val initial = inspect()
        if (initial != OutputState.PENDING) initial else if (deletePending() > 0) OutputState.ABSENT else inspect()
    } catch (_: Exception) {
        OutputState.UNKNOWN
    }
}
