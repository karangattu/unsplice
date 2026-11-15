package com.echomute

object AcousticAdDetector {
    private const val MIN_BREAK_MS = 15_000L
    private const val MAX_BREAK_MS = 120_000L
    private const val SURROUNDING_WINDOW_MS = 30_000L
    private const val BOUNDARY_WINDOW_MS = 3_500L
    private const val MIN_SILENCE_MS = 200L

    internal fun hasSilenceBoundary(
        envelope: IntArray,
        rateHz: Int,
        edgeMs: Long,
        windowMs: Long = BOUNDARY_WINDOW_MS
    ): Boolean {
        if (envelope.isEmpty() || rateHz <= 0) return false
        val center = ((edgeMs * rateHz) / 1000L).toInt()
        val radius = ((windowMs * rateHz) / 1000L).toInt()
        val from = (center - radius).coerceAtLeast(0)
        val to = (center + radius).coerceAtMost(envelope.size - 1)
        if (from >= to) return false

        val localMedian = sliceMedian(envelope, from, to)
        if (localMedian <= 0.0) return false
        val threshold = localMedian * 0.18
        val minFrames = maxOf(2, ((MIN_SILENCE_MS * rateHz) / 1000L).toInt())

        var run = 0
        for (i in from..to) {
            if (envelope[i] <= threshold) {
                run++
                if (run >= minFrames) return true
            } else {
                run = 0
            }
        }
        return false
    }

    internal fun isLoudnessJump(
        envelope: IntArray,
        rateHz: Int,
        segment: Segment,
        factor: Double = 1.32
    ): Boolean {
        if (envelope.isEmpty() || rateHz <= 0) return false
        val startIdx = ((segment.startMs * rateHz) / 1000L).toInt().coerceIn(0, envelope.size - 1)
        val endIdx = ((segment.endMs * rateHz) / 1000L).toInt().coerceIn(0, envelope.size - 1)
        if (endIdx <= startIdx) return false

        val insideMedian = sliceMedian(envelope, startIdx, endIdx)
        if (insideMedian <= 0.0) return false

        val padFrames = ((2_000L * rateHz) / 1000L).toInt()
        val surroundFrames = ((SURROUNDING_WINDOW_MS * rateHz) / 1000L).toInt()
        val leftFrom = (startIdx - padFrames - surroundFrames).coerceAtLeast(0)
        val leftTo = (startIdx - padFrames).coerceAtLeast(-1)
        val rightFrom = (endIdx + padFrames).coerceAtMost(envelope.size)
        val rightTo = (endIdx + padFrames + surroundFrames).coerceAtMost(envelope.size - 1)

        val outsideSamples = ArrayList<Int>()
        if (leftTo >= leftFrom) {
            for (i in leftFrom..leftTo) outsideSamples.add(envelope[i])
        }
        if (rightTo >= rightFrom) {
            for (i in rightFrom..rightTo) outsideSamples.add(envelope[i])
        }
        if (outsideSamples.size < maxOf(4, rateHz)) return false
        outsideSamples.sort()
        val outsideMedian = outsideSamples[outsideSamples.size / 2].toDouble()
        return outsideMedian > 0.0 && insideMedian >= outsideMedian * factor
    }

    internal fun corroborateSegments(
        scored: List<Pair<Segment, Float>>,
        envelope: IntArray,
        rateHz: Int,
        spliceSegments: List<Pair<Segment, Float>> = emptyList(),
        daiShifted: Boolean = false
    ): List<Pair<Segment, Float>> {
        if (scored.isEmpty()) return emptyList()
        return scored.map { (seg, conf) ->
            if (conf >= AUTO_SKIP_CONFIDENCE) {
                seg to conf
            } else {
                val len = seg.endMs - seg.startMs
                val spliceConfirmed = len > 0L && spliceSegments.any { (sp, _) ->
                    val overlap = minOf(seg.endMs, sp.endMs) - maxOf(seg.startMs, sp.startMs)
                    overlap > 0L && overlap * 2L >= len
                }
                val acousticConfirmed = !daiShifted && envelope.isNotEmpty() && rateHz > 0 && (
                    isLoudnessJump(envelope, rateHz, seg) ||
                        (hasSilenceBoundary(envelope, rateHz, seg.startMs) &&
                            hasSilenceBoundary(envelope, rateHz, seg.endMs))
                    )
                val boosted = if (spliceConfirmed || acousticConfirmed) maxOf(conf, 0.70f) else conf
                seg to boosted
            }
        }
    }

    internal fun detectLoudnessJumpBreaks(
        envelope: IntArray,
        rateHz: Int,
        durationMs: Long?
    ): List<Pair<Segment, Float>> {
        if (envelope.size < rateHz * 60 || rateHz <= 0) return emptyList()
        val totalMs = durationMs ?: ((envelope.size.toLong() * 1000L) / rateHz)
        if (totalMs < 90_000L) return emptyList()

        val globalMedian = sampledMedian(envelope)
        if (globalMedian <= 0.0) return emptyList()

        val silenceThresh = globalMedian * 0.18
        val minSilenceFrames = maxOf(2, ((250L * rateHz) / 1000L).toInt())

        val boundaries = ArrayList<Int>()
        boundaries.add(0)
        var i = 0
        while (i < envelope.size) {
            if (envelope[i] <= silenceThresh) {
                val runStart = i
                while (i < envelope.size && envelope[i] <= silenceThresh) i++
                if (i - runStart >= minSilenceFrames) {
                    boundaries.add((runStart + i) / 2)
                }
            } else {
                i++
            }
        }
        boundaries.add(envelope.size - 1)

        val out = ArrayList<Pair<Segment, Float>>()
        for (b in 0 until boundaries.size - 1) {
            val startMs = (boundaries[b].toLong() * 1000L) / rateHz
            val endMs = (boundaries[b + 1].toLong() * 1000L) / rateHz
            val len = endMs - startMs
            if (len < MIN_BREAK_MS || len > MAX_BREAK_MS) continue
            val seg = Segment(startMs, endMs)
            if (isLoudnessJump(envelope, rateHz, seg, factor = 1.60)) {
                out.add(seg to 0.55f)
            }
        }
        return out
    }

    internal fun refineTranscriptSegments(
        scored: List<Pair<Segment, Float>>,
        envelope: IntArray,
        rateHz: Int
    ): List<Pair<Segment, Float>> {
        if (scored.isEmpty() || envelope.isEmpty() || rateHz <= 0) return scored
        val frameMs = (1000 / rateHz).coerceAtLeast(1)
        val floatEnv = FloatArray(envelope.size) { envelope[it].toFloat() }
        return scored.map { (seg, conf) ->
            refineEdges(seg, floatEnv, frameMs = frameMs) to conf
        }
    }

    private fun sliceMedian(arr: IntArray, from: Int, to: Int): Double {
        val count = to - from + 1
        if (count <= 0) return 0.0
        val step = if (count > 512) count / 512 else 1
        val sample = IntArray((count + step - 1) / step)
        var k = 0
        var i = from
        while (i <= to && k < sample.size) {
            sample[k++] = arr[i]
            i += step
        }
        sample.sort(0, k)
        return sample[k / 2].toDouble()
    }

    private fun sampledMedian(arr: IntArray): Double {
        if (arr.isEmpty()) return 0.0
        val step = if (arr.size > 1024) arr.size / 1024 else 1
        val sample = IntArray((arr.size + step - 1) / step)
        var k = 0
        var i = 0
        while (i < arr.size && k < sample.size) {
            sample[k++] = arr[i]
            i += step
        }
        sample.sort(0, k)
        return sample[k / 2].toDouble()
    }
}
