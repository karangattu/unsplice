package com.echomute

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class DatesTest {
    private val today = LocalDate.of(2026, 10, 31)
    private fun ms(date: LocalDate) = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() + 12 * 3_600_000L

    @Test fun formatsPublishDatesRelativeToToday() {
        assertEquals("Today", formatPublished(ms(today), ZoneOffset.UTC, today))
        assertEquals("Yesterday", formatPublished(ms(today.minusDays(1)), ZoneOffset.UTC, today))
        assertEquals("3 Mar", formatPublished(ms(LocalDate.of(2026, 3, 3)), ZoneOffset.UTC, today))
        assertEquals("27 Dec 2025", formatPublished(ms(LocalDate.of(2025, 12, 27)), ZoneOffset.UTC, today))
    }

    @Test fun describesListeningProgress() {
        assertNull(progressLabel(0, 3_600_000, false))
        assertEquals("Played", progressLabel(0, 3_600_000, true))
        assertEquals("42 min left", progressLabel(1_080_000, 3_600_000, false))
        assertEquals("Less than a minute left", progressLabel(3_570_000, 3_600_000, false))
        assertEquals("In progress", progressLabel(5_000, null, false))
    }
}
