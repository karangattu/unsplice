package com.echomute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiHelpersTest {
    @Test fun cyclesSpeedPresets() {
        assertEquals(1.25f, nextSpeedPreset(1f), 0.001f)
        assertEquals(1.5f, nextSpeedPreset(1.25f), 0.001f)
        assertEquals(2f, nextSpeedPreset(1.5f), 0.001f)
        assertEquals(1f, nextSpeedPreset(2f), 0.001f)
        assertEquals(1f, nextSpeedPreset(1.3f), 0.001f)
    }

    @Test fun cyclesIntOptionsAndCustomSpeed() {
        val options = listOf(0, 15, 30, 45, 60)
        assertEquals(15, nextCycleInt(0, options))
        assertEquals(60, nextCycleInt(45, options))
        assertEquals(0, nextCycleInt(60, options))
        assertEquals(0, nextCycleInt(99, options))

        assertEquals(1.2f, nextCustomSpeed(null)!!, 0.001f)
        assertEquals(1.5f, nextCustomSpeed(1.2f)!!, 0.001f)
        assertEquals(2.0f, nextCustomSpeed(1.5f)!!, 0.001f)
        assertNull(nextCustomSpeed(2.0f))
    }

    @Test fun cyclesSleepTimerStates() {
        val now = 1_000_000L
        val step1 = nextSleepTimerUpdate(now, 0L, false)
        assertEquals("15m", step1.label)
        assertEquals(false, step1.endOfEpisode)
        assertEquals(now + 15 * 60_000L, step1.untilEpochMs)

        val step2 = nextSleepTimerUpdate(now, now + 10 * 60_000L, false)
        assertEquals("30m", step2.label)
        assertEquals(now + 30 * 60_000L, step2.untilEpochMs)

        val step3 = nextSleepTimerUpdate(now, now + 25 * 60_000L, false)
        assertEquals("60m", step3.label)
        assertEquals(now + 60 * 60_000L, step3.untilEpochMs)

        val step4 = nextSleepTimerUpdate(now, now + 45 * 60_000L, false)
        assertEquals("End of ep", step4.label)
        assertEquals(true, step4.endOfEpisode)
        assertEquals(0L, step4.untilEpochMs)

        val step5 = nextSleepTimerUpdate(now, 0L, true)
        assertEquals("Off", step5.label)
        assertEquals(false, step5.endOfEpisode)
        assertEquals(0L, step5.untilEpochMs)
    }

    @Test fun formatsLibraryShowAndQueueSummaries() {
        assertEquals("1 show", formatLibrarySummary(1, 0))
        assertEquals("3 shows \u2022 7 unplayed", formatLibrarySummary(3, 7))

        assertEquals("1 episode \u2022 All played", formatShowEpisodeCount(1, 0))
        assertEquals("12 episodes \u2022 4 unplayed", formatShowEpisodeCount(12, 4))

        assertEquals("Your listening queue", formatQueueSummary(0))
        assertEquals("1 episode in queue", formatQueueSummary(1))
        assertEquals("5 episodes in queue", formatQueueSummary(5))
    }
}
