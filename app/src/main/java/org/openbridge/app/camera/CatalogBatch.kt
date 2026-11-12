package org.openbridge.app.camera

import org.openbridge.app.domain.SortOrder

internal object CatalogBatchSize {
    const val DEFAULT = 50
    const val UNLIMITED_PREF = "unlimited"
    val presets = listOf(25, 50, 100, 200)
    fun valid(size: Int?): Boolean = size == null || size in 1..5000
    fun fromPreference(value: Any?): Int? = if (value == UNLIMITED_PREF) null else (value as? Int)?.takeIf(::valid) ?: DEFAULT
}

internal enum class CatalogDirection { NEWEST, OLDEST }

internal fun SortOrder.catalogDirection() = if (this == SortOrder.OLDEST) CatalogDirection.OLDEST else CatalogDirection.NEWEST

/** MTP enumeration approximates capture order by handle order within each storage. Handles carry no capture timestamp. */
internal fun orderedMtpHandles(handles: LongArray, direction: CatalogDirection): List<Long> =
    if (direction == CatalogDirection.OLDEST) handles.asList() else handles.asList().asReversed()

data class SonyCaptureDay(val slot: Int, val localMillis: Long) {
    val epochDay: Long = Math.floorDiv(localMillis, DAY_MILLIS)
    internal fun containsTime(localTime: Long) = Math.floorDiv(localTime, DAY_MILLIS) == epochDay
    private companion object { const val DAY_MILLIS = 86_400_000L }
}
