package com.echomute

import java.util.Arrays

data class Fingerprint(val hash: Int, val timeMs: Long)
data class Segment(val startMs: Long, val endMs: Long)

private const val OFFSET_BIAS = 1L shl 20
private const val MAX_OFFSET_BIASED = (1L shl 21) - 1L
private const val SEC_MASK = 0xFFFFFL
private const val MIN_BEGIN_MS = 180_000L
private const val MIN_SEGMENT_MS = 15_000L
private const val MAX_SEGMENT_MS = 180_000L
private const val MIN_DISTINCT_SECONDS = 15
const val DEFAULT_MAX_HASH_BUCKET = 64

class PackedHashIndex(val packed: LongArray, val maxBucketSize: Int = DEFAULT_MAX_HASH_BUCKET) {
    val mask: Int
    val keys: IntArray
    val heads: IntArray
    val counts: IntArray
    val next: IntArray
    val timesMs: LongArray

    init {
        val n = packed.size
        var cap = 16
        while (cap < n * 2) cap = cap shl 1
        mask = cap - 1
        keys = IntArray(cap)
        heads = IntArray(cap) { -1 }
        counts = IntArray(cap)
        next = IntArray(n)
        timesMs = LongArray(n)
        for (i in 0 until n) {
            val p = packed[i]
            val h = (p ushr 32).toInt()
            timesMs[i] = p and 0xffffffffL
            var slot = (h * -1640531527) and mask
            while (counts[slot] != 0 && keys[slot] != h) {
                slot = (slot + 1) and mask
            }
            keys[slot] = h
            counts[slot]++
            next[i] = heads[slot]
            heads[slot] = i
        }
    }

    fun findSlot(hash: Int): Int {
        if (packed.isEmpty()) return -1
        var slot = (hash * -1640531527) and mask
        while (counts[slot] != 0) {
            if (keys[slot] == hash) return slot
            slot = (slot + 1) and mask
        }
        return -1
    }
}

object MatchFinder {
    private fun mergeSegments(segments: List<Segment>): List<Segment> {
        if (segments.size <= 1) return segments
        val merged = ArrayList<Segment>(segments.size)
        for (segment in segments.sortedBy { it.startMs }) {
            val last = merged.lastOrNull()
            if (last != null && segment.startMs <= last.endMs) {
                merged[merged.lastIndex] = Segment(last.startMs, maxOf(last.endMs, segment.endMs))
            } else {
                merged.add(segment)
            }
        }
        return merged
    }

    private fun extractSegments(offsets: Map<Long, Set<Long>>, minBeginMs: Long): List<Segment> {
        val found = mutableListOf<Segment>()
        for (seconds in offsets.values) {
            if (seconds.size < MIN_DISTINCT_SECONDS) continue
            val sorted = seconds.sorted()
            var start = 0
            for (i in sorted.indices) {
                if (i == sorted.lastIndex || sorted[i + 1] - sorted[i] > 2) {
                    val begin = sorted[start] * 1000
                    val end = (sorted[i] + 1) * 1000
                    if (begin >= minBeginMs && end - begin in MIN_SEGMENT_MS until MAX_SEGMENT_MS && i - start >= MIN_DISTINCT_SECONDS - 1) {
                        found.add(Segment(begin, end))
                    }
                    start = i + 1
                }
            }
        }
        return mergeSegments(found)
    }

    private fun extractFromHits(hits: LongArray, len: Int, minBeginMs: Long): List<Segment> {
        if (len < MIN_DISTINCT_SECONDS) return emptyList()
        Arrays.sort(hits, 0, len)
        val found = ArrayList<Segment>()
        var i = 0
        while (i < len) {
            val off = hits[i] ushr 20
            val groupStart = i
            while (i < len && (hits[i] ushr 20) == off) i++
            if (i - groupStart < MIN_DISTINCT_SECONDS) continue

            var runStartSec = hits[groupStart] and SEC_MASK
            var prevSec = runStartSec
            var distinctCount = 1
            for (k in groupStart + 1 until i) {
                val sec = hits[k] and SEC_MASK
                if (sec == prevSec) continue
                if (sec - prevSec > 2L) {
                    val begin = runStartSec * 1000L
                    val end = (prevSec + 1L) * 1000L
                    if (begin >= minBeginMs && end - begin in MIN_SEGMENT_MS until MAX_SEGMENT_MS && distinctCount >= MIN_DISTINCT_SECONDS) {
                        found.add(Segment(begin, end))
                    }
                    runStartSec = sec
                    distinctCount = 1
                } else {
                    distinctCount++
                }
                prevSec = sec
            }
            val begin = runStartSec * 1000L
            val end = (prevSec + 1L) * 1000L
            if (begin >= minBeginMs && end - begin in MIN_SEGMENT_MS until MAX_SEGMENT_MS && distinctCount >= MIN_DISTINCT_SECONDS) {
                found.add(Segment(begin, end))
            }
        }
        return mergeSegments(found)
    }

    fun crossEpisodeBidirectional(
        current: PackedHashIndex,
        other: LongArray,
        minBeginMs: Long = MIN_BEGIN_MS
    ): Pair<List<Segment>, List<Segment>> {
        if (current.packed.isEmpty() || other.isEmpty()) return emptyList<Segment>() to emptyList()
        var hitsA = LongArray(4096)
        var hitsB = LongArray(4096)
        var nA = 0
        var nB = 0
        for (p in other) {
            val h = (p ushr 32).toInt()
            val slot = current.findSlot(h)
            if (slot < 0 || current.counts[slot] > current.maxBucketSize) continue
            val timeB = p and 0xffffffffL
            val secB = timeB / 1000L
            if (secB >= SEC_MASK) continue
            var node = current.heads[slot]
            while (node >= 0) {
                val timeA = current.timesMs[node]
                val secA = timeA / 1000L
                if (secA < SEC_MASK) {
                    val offA = (timeA - timeB) / 1000L + OFFSET_BIAS
                    val offB = (timeB - timeA) / 1000L + OFFSET_BIAS
                    if (offA in 0L..MAX_OFFSET_BIASED && offB in 0L..MAX_OFFSET_BIASED) {
                        if (nA == hitsA.size) hitsA = hitsA.copyOf(hitsA.size * 2)
                        if (nB == hitsB.size) hitsB = hitsB.copyOf(hitsB.size * 2)
                        hitsA[nA++] = (offA shl 20) or secA
                        hitsB[nB++] = (offB shl 20) or secB
                    }
                }
                node = current.next[node]
            }
        }
        return extractFromHits(hitsA, nA, minBeginMs) to extractFromHits(hitsB, nB, minBeginMs)
    }

    fun crossEpisodePacked(current: LongArray, other: LongArray, minBeginMs: Long = MIN_BEGIN_MS): List<Segment> =
        crossEpisodeBidirectional(PackedHashIndex(current), other, minBeginMs).first

    fun intraEpisodePacked(
        packed: LongArray,
        maxBucketSize: Int = DEFAULT_MAX_HASH_BUCKET,
        minBeginMs: Long = MIN_BEGIN_MS
    ): List<Segment> {
        if (packed.isEmpty()) return emptyList()
        val index = PackedHashIndex(packed, maxBucketSize)
        var hitsFirst = LongArray(4096)
        var hitsSecond = LongArray(4096)
        var nFirst = 0
        var nSecond = 0
        for (p in packed) {
            val h = (p ushr 32).toInt()
            val slot = index.findSlot(h)
            if (slot < 0 || index.counts[slot] > index.maxBucketSize) continue
            val timeA = p and 0xffffffffL
            val secA = timeA / 1000L
            if (secA >= SEC_MASK) continue
            var node = index.heads[slot]
            while (node >= 0) {
                val timeB = index.timesMs[node]
                if (timeB - timeA >= 60_000L) {
                    val secB = timeB / 1000L
                    if (secB < SEC_MASK) {
                        val off = (timeB - timeA) / 1000L + OFFSET_BIAS
                        if (off in 0L..MAX_OFFSET_BIASED) {
                            if (nFirst == hitsFirst.size) hitsFirst = hitsFirst.copyOf(hitsFirst.size * 2)
                            if (nSecond == hitsSecond.size) hitsSecond = hitsSecond.copyOf(hitsSecond.size * 2)
                            hitsFirst[nFirst++] = (off shl 20) or secA
                            hitsSecond[nSecond++] = (off shl 20) or secB
                        }
                    }
                }
                node = index.next[node]
            }
        }
        val first = extractFromHits(hitsFirst, nFirst, minBeginMs)
        val second = extractFromHits(hitsSecond, nSecond, minBeginMs)
        if (first.isEmpty()) return second
        if (second.isEmpty()) return first
        return mergeSegments(first + second)
    }

    fun crossEpisodeWithPrecomputedMap(current: List<Fingerprint>, byHash: Map<Int, List<Fingerprint>>): List<Segment> {
        val offsets = mutableMapOf<Long, MutableSet<Long>>()
        for (a in current) {
            val matches = byHash[a.hash] ?: continue
            val second = a.timeMs / 1000
            for (b in matches) {
                val offset = (a.timeMs - b.timeMs) / 1000
                offsets.getOrPut(offset) { mutableSetOf() }.add(second)
            }
        }
        return extractSegments(offsets, minBeginMs = MIN_BEGIN_MS)
    }

    fun crossEpisode(current: List<Fingerprint>, other: List<Fingerprint>): List<Segment> =
        crossEpisodeWithPrecomputedMap(current, other.groupBy { it.hash })

    fun intraEpisode(fingerprints: List<Fingerprint>): List<Segment> {
        val byHash = fingerprints.groupBy { it.hash }
        val offsetsFirst = mutableMapOf<Long, MutableSet<Long>>()
        val offsetsSecond = mutableMapOf<Long, MutableSet<Long>>()
        for (a in fingerprints) {
            for (b in byHash[a.hash].orEmpty()) {
                if (b.timeMs - a.timeMs >= 60_000) {
                    val offset = (b.timeMs - a.timeMs) / 1000
                    offsetsFirst.getOrPut(offset) { mutableSetOf() }.add(a.timeMs / 1000)
                    offsetsSecond.getOrPut(offset) { mutableSetOf() }.add(b.timeMs / 1000)
                }
            }
        }
        val first = extractSegments(offsetsFirst, minBeginMs = MIN_BEGIN_MS)
        val second = extractSegments(offsetsSecond, minBeginMs = MIN_BEGIN_MS)
        return mergeSegments(first + second)
    }
}

fun skipTarget(positionMs: Long, segments: List<Segment>, enabled: Boolean): Long? =
    if (!enabled) null else segments.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs - 500 }?.endMs
