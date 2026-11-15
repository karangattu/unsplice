package com.echomute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AcousticAdDetectorTest {
    @Test
    fun offersALoudBreakBoundedBySilenceAsAManualSkip() {
        val env = IntArray(1800) { 1000 }
        for (i in 596..599) env[i] = 50
        for (i in 600 until 900) env[i] = 1900
        for (i in 900..903) env[i] = 50

        val (segment, confidence) = AcousticAdDetector.detectLoudnessJumpBreaks(env, 10).single()
        assertTrue(segment.startMs in 59_500L..60_500L)
        assertTrue(segment.endMs in 89_500L..90_500L)
        assertTrue(confidence < AUTO_SKIP_CONFIDENCE)
    }

    @Test
    fun doesNotFlagUniformAudio() {
        assertTrue(AcousticAdDetector.detectLoudnessJumpBreaks(IntArray(1800) { 1000 }, 10).isEmpty())
    }

    @Test
    fun refinesLateTranscriptEdgesAtAnEnvelopeRateThatDoesNotDivide1000() {
        val env = IntArray(7 * 3720) { 1000 }
        for (i in (7 * 3601) until (7 * 3602)) env[i] = 10
        for (i in (7 * 3631) until (7 * 3632)) env[i] = 10
        val scored = listOf(Segment(3_600_000, 3_630_000) to TRANSCRIPT_CONFIDENCE)
        val result = AcousticAdDetector.refineTranscriptSegments(scored, env, 7, timestampsAligned = true)
        assertEquals(listOf(Segment(3_602_000, 3_631_000) to TRANSCRIPT_CONFIDENCE), result)
    }

    @Test
    fun doesNotRefineTranscriptWithUnverifiedTimestamps() {
        val env = IntArray(1800) { 1000 }
        for (i in 604..608) env[i] = 10
        val scored = listOf(Segment(60_000, 90_000) to TRANSCRIPT_CONFIDENCE)
        assertEquals(scored, AcousticAdDetector.refineTranscriptSegments(scored, env, 10, timestampsAligned = false))
    }
}
