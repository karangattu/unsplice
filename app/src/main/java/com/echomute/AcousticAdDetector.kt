package com.echomute

internal object AcousticAdDetector {
    private const val MIN_BREAK_MS = 15_000L
    private const val MAX_BREAK_MS = 120_000L
    private const val SURROUNDING_WINDOW_MS = 30_000L

    private fun isLoudnessJump(envelope: IntArray, rateHz: Int, start: Int, end: Int): Boolean {
        val insideMedian = sampledMedian(envelope, start, end + 1, 512)
        if (insideMedian <= 0) return false

        val pad = 2 * rateHz
        val surround = ((SURROUNDING_WINDOW_MS * rateHz) / 1000L).toInt()
        val leftEnd = (start - pad).coerceAtLeast(0)
        val leftStart = (leftEnd - surround).coerceAtLeast(0)
        val rightStart = (end + pad).coerceAtMost(envelope.size)
        val rightEnd = (rightStart + surround).coerceAtMost(envelope.size)
        val outside = IntArray(leftEnd - leftStart + rightEnd - rightStart)
        if (outside.size < maxOf(4, rateHz)) return false
        envelope.copyInto(outside, startIndex = leftStart, endIndex = leftEnd)
        envelope.copyInto(outside, destinationOffset = leftEnd - leftStart, startIndex = rightStart, endIndex = rightEnd)
        val outsideMedian = sampledMedian(outside, 0, outside.size, 512)
        return outsideMedian > 0 && insideMedian >= outsideMedian * 1.60
    }

    internal fun detectLoudnessJumpBreaks(
        envelope: IntArray,
        rateHz: Int
    ): List<Pair<Segment, Float>> {
        if (rateHz <= 0 || envelope.size < 90L * rateHz) return emptyList()
        val globalMedian = sampledMedian(envelope, 0, envelope.size, 1024)
        if (globalMedian <= 0) return emptyList()

        val silenceThreshold = globalMedian * 0.18
        val minSilenceFrames = maxOf(2, (rateHz + 3) / 4)
        val breaks = ArrayList<Pair<Segment, Float>>()
        var start = 0
        fun checkBreak(end: Int) {
            val startMs = start * 1000L / rateHz
            val endMs = end * 1000L / rateHz
            if (endMs - startMs in MIN_BREAK_MS..MAX_BREAK_MS && isLoudnessJump(envelope, rateHz, start, end)) {
                breaks.add(Segment(startMs, endMs) to 0.45f)
            }
            start = end
        }

        var i = 0
        while (i < envelope.size) {
            if (envelope[i] > silenceThreshold) {
                i++
                continue
            }
            val runStart = i
            while (i < envelope.size && envelope[i] <= silenceThreshold) i++
            if (i - runStart >= minSilenceFrames) checkBreak(runStart + (i - runStart) / 2)
        }
        checkBreak(envelope.lastIndex)
        return breaks
    }

    internal fun refineTranscriptSegments(
        scored: List<Pair<Segment, Float>>,
        envelope: IntArray,
        rateHz: Int,
        timestampsAligned: Boolean
    ): List<Pair<Segment, Float>> {
        if (!timestampsAligned || envelope.isEmpty() || rateHz <= 0) return scored
        return scored.map { (segment, confidence) ->
            if (confidence >= AUTO_SKIP_CONFIDENCE) segment to confidence
            else refineEdges(segment, envelope, rateHz) to confidence
        }
    }

    private fun sampledMedian(values: IntArray, start: Int, end: Int, limit: Int): Int {
        val count = end - start
        val step = (count - 1) / limit + 1
        val sample = IntArray((count - 1) / step + 1) { values[start + it * step] }
        sample.sort()
        return sample[sample.size / 2]
    }
}
