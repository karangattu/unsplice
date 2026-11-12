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
    @Test fun findsIntraEpisodeRepetitions() {
        val first = (0..20).map { Fingerprint(it, 200_000L + it * 1_000) }
        val second = (0..20).map { Fingerprint(it, 600_000L + it * 1_000) }
        val segments = MatchFinder.intraEpisode(first + second)
        assertEquals(listOf(Segment(200_000, 221_000), Segment(600_000, 621_000)), segments)
    }
    @Test fun crossEpisodeWithPrecomputedMapMatchesStandardCrossEpisode() {
        val current = (0..20).map { Fingerprint(it, 240_000L + it * 1_000) }
        val other = (0..20).map { Fingerprint(it, 500_000L + it * 1_000) }
        val map = other.groupBy { it.hash }
        assertEquals(MatchFinder.crossEpisode(current, other), MatchFinder.crossEpisodeWithPrecomputedMap(current, map))
    }
    @Test fun skipsOnlyWithinSegmentAndWhenEnabled() {
        val ads = listOf(Segment(1_000, 5_000))
        assertEquals(5_000L, skipTarget(1_000, ads, true))
        assertNull(skipTarget(4_600, ads, true))
        assertNull(skipTarget(2_000, ads, false))
    }
    @Test fun bidirectionalPackedMatcherMatchesBothDirectionsInOnePass() {
        val current = (0..25).map { packFingerprint(it * 17 + 3, 240_000L + it * 1_000) }.toLongArray()
        val other = (0..25).map { packFingerprint(it * 17 + 3, 520_000L + it * 1_000) }.toLongArray()
        val (forCurrent, forOther) = MatchFinder.crossEpisodeBidirectional(PackedHashIndex(current), other)
        assertEquals(listOf(Segment(240_000, 266_000)), forCurrent)
        assertEquals(listOf(Segment(520_000, 546_000)), forOther)
    }
    @Test fun packedIntraEpisodeMatchesListImplementation() {
        val first = (0..20).map { packFingerprint(it + 100, 200_000L + it * 1_000) }
        val second = (0..20).map { packFingerprint(it + 100, 600_000L + it * 1_000) }
        val packed = (first + second).toLongArray()
        assertEquals(listOf(Segment(200_000, 221_000), Segment(600_000, 621_000)), MatchFinder.intraEpisodePacked(packed))
    }
    @Test fun packedHashIndexCapsPathologicalBucketSize() {
        val noisy = (0..80).map { packFingerprint(999, 200_000L + it * 1_000) }.toLongArray()
        val other = (0..20).map { packFingerprint(999, 400_000L + it * 1_000) }.toLongArray()
        val (forCurrent, forOther) = MatchFinder.crossEpisodeBidirectional(PackedHashIndex(noisy, maxBucketSize = 64), other)
        assertTrue(forCurrent.isEmpty())
        assertTrue(forOther.isEmpty())
    }
}
