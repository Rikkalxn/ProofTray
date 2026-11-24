package org.prooftray.app.domain

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

internal fun FilterSpec.hasConditions(): Boolean = copy(sortOrder = SortOrder.NEWEST) != FilterSpec()

internal fun FilterSpec.hasAdvancedConditions(): Boolean = this != FilterSpec(text = text, formats = formats,
    minimumRating = minimumRating, maximumRating = maximumRating, pickStates = pickStates, downloadStates = downloadStates, sortOrder = sortOrder)

// Material DateRangePicker represents calendar dates at midnight UTC. Catalog filters use local-day boundaries.
// https://developer.android.com/reference/kotlin/androidx/compose/material3/DateRangePickerState
internal fun pickerDayMillis(timestamp: Long?, zone: ZoneId): Long? = timestamp?.let {
    Instant.ofEpochMilli(it).atZone(zone).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
}

internal fun filterDayMillis(pickerMillis: Long?, endOfDay: Boolean, zone: ZoneId): Long? = pickerMillis?.let {
    val date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()
    (if (endOfDay) date.plusDays(1) else date).atStartOfDay(zone).toInstant().toEpochMilli() - if (endOfDay) 1 else 0
}
