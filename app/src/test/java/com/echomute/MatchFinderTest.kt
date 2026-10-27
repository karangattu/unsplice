package com.echomute

import org.junit.Assert.*
import org.junit.Test

class MatchFinderTest {
    @Test fun findsCoherentRepetitionAndRejectsIntroAndLongMatches() {
        val current = (0..20).map { Fingerprint(it, 240_000L + it * 1_000) }
        val other = (0..20).map { Fingerprint(it, 500_000L + it * 1_000) }
        assertEquals(listOf(Segment(240_000, 261_000)), MatchFinder.crossEpisode(current, other))
        assertTrue(MatchFinder.crossEpisode(current.map { it.copy(timeMs = it.timeMs - 200_000) }, other).isEmpty())
        val longA = (0..180).map { Fingerprint(it, 240_000L + it * 1_000) }
        val longB = (0..180).map { Fingerprint(it, 500_000L + it * 1_000) }
        assertTrue(MatchFinder.crossEpisode(longA, longB).isEmpty())
    }
    @Test fun ignoresScatteredCollisions() {
        val a = (0..20).map { Fingerprint(it, 240_000L + it * 1_000) }
        val b = (0..20).map { Fingerprint(it, 500_000L + it * 3_000) }
        assertTrue(MatchFinder.crossEpisode(a, b).isEmpty())
    }
    @Test fun skipsOnlyWithinSegmentAndWhenEnabled() {
        val ads = listOf(Segment(1_000, 5_000))
        assertEquals(5_000L, skipTarget(1_000, ads, true))
        assertNull(skipTarget(4_600, ads, true))
        assertNull(skipTarget(2_000, ads, false))
    }
}
