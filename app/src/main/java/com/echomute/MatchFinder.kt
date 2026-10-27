package com.echomute

data class Fingerprint(val hash: Int, val timeMs: Long)
data class Segment(val startMs: Long, val endMs: Long)

object MatchFinder {
    fun crossEpisode(current: List<Fingerprint>, other: List<Fingerprint>): List<Segment> {
        val byHash = other.groupBy { it.hash }
        val offsets = mutableMapOf<Long, MutableSet<Long>>()
        for (a in current) for (b in byHash[a.hash].orEmpty()) {
            val second = a.timeMs / 1000
            val offset = (a.timeMs - b.timeMs) / 1000
            offsets.getOrPut(offset) { mutableSetOf() }.add(second)
        }
        val found = mutableListOf<Segment>()
        for (seconds in offsets.values) {
            val sorted = seconds.sorted()
            var start = 0
            for (i in sorted.indices) {
                if (i == sorted.lastIndex || sorted[i + 1] - sorted[i] > 2) {
                    val begin = sorted[start] * 1000
                    val end = (sorted[i] + 1) * 1000
                    if (begin >= 180_000 && end - begin in 15_000 until 180_000 && i - start >= 14) found.add(Segment(begin, end))
                    start = i + 1
                }
            }
        }
        return found.sortedBy { it.startMs }.fold(mutableListOf()) { merged, segment ->
            val last = merged.lastOrNull()
            if (last != null && segment.startMs <= last.endMs) merged[merged.lastIndex] = Segment(last.startMs, maxOf(last.endMs, segment.endMs))
            else merged.add(segment)
            merged
        }
    }
}

fun skipTarget(positionMs: Long, segments: List<Segment>, enabled: Boolean): Long? =
    if (!enabled) null else segments.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs - 500 }?.endMs
