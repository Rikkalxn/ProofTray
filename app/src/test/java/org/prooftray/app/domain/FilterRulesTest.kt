package org.prooftray.app.domain

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class FilterRulesTest {
    @Test fun sortingIsIndependentOfFilterConditions() {
        assertFalse(FilterSpec(sortOrder = SortOrder.OLDEST).hasConditions())
        assertFalse(FilterSpec(text = "DSC", formats = setOf(MediaFormat.RAW), minimumRating = 2,
            pickStates = setOf(PickState.PICKED), sortOrder = SortOrder.NAME).hasAdvancedConditions())
        assertTrue(FilterSpec(camera = "Nikon").hasAdvancedConditions())
        assertTrue(FilterSpec(capturedAfterMillis = 123).hasAdvancedConditions())
        assertTrue(FilterSpec(includeUnknownMetadata = false).hasAdvancedConditions())
    }

    @Test fun utcPickerDateBecomesTheSameCalendarDayInShanghai() {
        val zone = ZoneId.of("Asia/Shanghai")
        val picker = Instant.parse("2026-11-11T00:00:00Z").toEpochMilli()
        assertEquals(Instant.parse("2026-11-10T16:00:00Z").toEpochMilli(), filterDayMillis(picker, false, zone))
        assertEquals(Instant.parse("2026-11-11T16:00:00Z").toEpochMilli() - 1, filterDayMillis(picker, true, zone))
        assertEquals(picker, pickerDayMillis(filterDayMillis(picker, true, zone), zone))
    }

    @Test fun daylightSavingDaysUseCalendarBoundariesInsteadOfTwentyFourHours() {
        val zone = ZoneId.of("America/Los_Angeles")
        for ((day, hours) in listOf("2026-03-08" to 23L, "2026-11-01" to 25L)) {
            val picker = Instant.parse(day + "T00:00:00Z").toEpochMilli()
            val from = checkNotNull(filterDayMillis(picker, false, zone))
            val until = checkNotNull(filterDayMillis(picker, true, zone))
            assertEquals(hours * 3600000, until - from + 1)
            assertEquals(picker, pickerDayMillis(from, zone))
        }
    }

    @Test fun openDateBoundsStayUnsetUntilTheUserSelectsADate() {
        val zone = ZoneId.of("Asia/Shanghai")
        assertNull(pickerDayMillis(null, zone))
        assertNull(filterDayMillis(null, false, zone))
        assertNull(filterDayMillis(null, true, zone))
    }
}
