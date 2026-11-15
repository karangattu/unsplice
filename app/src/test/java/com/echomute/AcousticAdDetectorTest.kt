package com.echomute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AcousticAdDetectorTest {
    @Test
    fun corroboratesWeakTranscriptSegmentOnLoudnessJumpAndSilenceEdges() {
        // 180s at 10 Hz = 1800 frames; ad break at 60s..90s (600..900)
        val env = IntArray(1800) { 1000 }
        for (i in 595..599) env[i] = 40 // 500ms silence before ad
        for (i in 600 until 900) env[i] = 1800 // louder ad
        for (i in 900..904) env[i] = 40 // 500ms silence after ad

        val seg = Segment(60_000L, 90_000L)
        assertTrue(AcousticAdDetector.hasSilenceBoundary(env, 10, 60_000L))
        assertTrue(AcousticAdDetector.hasSilenceBoundary(env, 10, 90_000L))
        assertTrue(AcousticAdDetector.isLoudnessJump(env, 10, seg))

        val corroborated = AcousticAdDetector.corroborateSegments(
            scored = listOf(seg to TRANSCRIPT_CONFIDENCE),
            envelope = env,
            rateHz = 10
        )
        assertEquals(1, corroborated.size)
        assertTrue(corroborated[0].second >= AUTO_SKIP_CONFIDENCE)
    }

    @Test
    fun doesNotBoostUncorroboratedUniformAudio() {
        val env = IntArray(1800) { 1000 }
        val seg = Segment(60_000L, 90_000L)
        assertFalse(AcousticAdDetector.hasSilenceBoundary(env, 10, 60_000L))
        assertFalse(AcousticAdDetector.isLoudnessJump(env, 10, seg))

        val result = AcousticAdDetector.corroborateSegments(
            scored = listOf(seg to TRANSCRIPT_CONFIDENCE),
            envelope = env,
            rateHz = 10
        )
        assertEquals(TRANSCRIPT_CONFIDENCE, result[0].second, 0.0001f)
    }

    @Test
    fun detectsLoudnessJumpBreakBoundedBySilence() {
        val env = IntArray(1800) { 1000 }
        for (i in 596..599) env[i] = 50
        for (i in 600 until 900) env[i] = 1900 // +5.5 dB ad
        for (i in 900..903) env[i] = 50

        val breaks = AcousticAdDetector.detectLoudnessJumpBreaks(env, 10, 180_000L)
        assertEquals(1, breaks.size)
        assertTrue(breaks[0].first.startMs in 59_500L..60_500L)
        assertTrue(breaks[0].first.endMs in 89_500L..90_500L)
        assertTrue(breaks[0].second >= AUTO_SKIP_CONFIDENCE)
    }

    @Test
    fun corroboratesWeakSegmentWhenSpliceOverlapsEvenIfDaiShifted() {
        val seg = Segment(60_000L, 90_000L)
        val splice = Segment(59_000L, 91_000L) to 0.75f
        val result = AcousticAdDetector.corroborateSegments(
            scored = listOf(seg to 0.4f),
            envelope = IntArray(0),
            rateHz = 10,
            spliceSegments = listOf(splice),
            daiShifted = true
        )
        assertTrue(result[0].second >= AUTO_SKIP_CONFIDENCE)
    }
}
