package com.echomute

import java.io.File
import java.io.RandomAccessFile

internal object Mp3SpliceDetector {
    private val BITRATES_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val BITRATES_V2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    private val SAMPLE_RATES = arrayOf(intArrayOf(11025, 12000, 8000), intArrayOf(),
        intArrayOf(22050, 24000, 16000), intArrayOf(44100, 48000, 32000))

    private const val NS_PER_MS = 1_000_000L
    private const val MIN_RUN_NS = 500 * NS_PER_MS
    private const val MIN_SPLICE_MS = 12_000L
    private const val MAX_MID_SPLICE_MS = 210_000L
    private const val MAX_EDGE_SPLICE_MS = 240_000L
    private const val BUFFER_SIZE = 65_536

    private data class FrameRun(val key: Int, val startNs: Long, val endNs: Long) {
        fun segment() = Segment(startNs / NS_PER_MS, endNs / NS_PER_MS)
    }

    private class Runs(keyCount: Int) {
        private val totals = LongArray(keyCount)
        private val runs = ArrayList<FrameRun>()
        private var key = -1
        private var startNs = 0L

        fun add(frameKey: Int, elapsedNs: Long, frameNs: Long) {
            if (frameKey != key) {
                finish(elapsedNs)
                key = frameKey
                startNs = elapsedNs
            }
            totals[frameKey] += frameNs
        }

        private fun finish(elapsedNs: Long) {
            if (key >= 0 && elapsedNs - startNs >= MIN_RUN_NS) {
                runs.add(FrameRun(key, startNs, elapsedNs))
            }
        }

        fun deviations(elapsedNs: Long): List<Segment>? {
            val dominant = totals.indices.maxBy { totals[it] }
            if (totals[dominant] < elapsedNs * 7 / 10) return null
            finish(elapsedNs)
            return runs.mapNotNull { if (it.key != dominant) it.segment() else null }
        }
    }

    internal fun detect(file: File, checkCancelled: () -> Unit = {}): List<Pair<Segment, Float>> {
        if (!file.isFile || file.length() < 4096L) return emptyList()
        val structure = Runs(32)
        val bitrate = Runs(512)
        val elapsedNs = RandomAccessFile(file, "r").use { raf ->
            val fileLen = raf.length()
            val buf = ByteArray(BUFFER_SIZE)
            var bufStart = 0L
            var bufLen = 0
            var pos = 0
            var elapsedNs = 0L

            fun ensureBytes(need: Int): Boolean {
                if (bufLen - pos >= need) return true
                checkCancelled()
                val remaining = bufLen - pos
                if (remaining > 0) System.arraycopy(buf, pos, buf, 0, remaining)
                bufStart += pos
                pos = 0
                bufLen = remaining
                while (bufLen < need && bufStart + bufLen < fileLen) {
                    val toRead = minOf((buf.size - bufLen).toLong(), fileLen - bufStart - bufLen).toInt()
                    val n = raf.read(buf, bufLen, toRead)
                    if (n <= 0) break
                    bufLen += n
                }
                return bufLen >= need
            }

            fun skip(byteCount: Int) {
                if (byteCount <= bufLen - pos) {
                    pos += byteCount
                } else {
                    bufStart += pos.toLong() + byteCount
                    raf.seek(bufStart)
                    bufLen = 0
                    pos = 0
                }
            }

            while (ensureBytes(4)) {
                val b0 = buf[pos].toInt() and 0xFF
                val b1 = buf[pos + 1].toInt() and 0xFF
                val b2 = buf[pos + 2].toInt() and 0xFF
                val b3 = buf[pos + 3].toInt() and 0xFF
                val remaining = fileLen - bufStart - pos
                if (b0 == 'T'.code && b1 == 'A'.code && b2 == 'G'.code && remaining == 128L) break
                if (b0 == 'I'.code && b1 == 'D'.code && b2 == '3'.code) {
                    if (b3 !in 2..4 || !ensureBytes(10)) return emptyList()
                    var size = 0
                    for (i in pos + 6..pos + 9) {
                        val part = buf[i].toInt()
                        if (part < 0) return emptyList()
                        size = (size shl 7) or part
                    }
                    val tagSize = 10 + size + if (b3 == 4 && (buf[pos + 5].toInt() and 0x10) != 0) 10 else 0
                    if (tagSize > remaining) return emptyList()
                    skip(tagSize)
                    continue
                }
                // Only inspect MP3 frame boundaries. Searching arbitrary payload bytes invents splices in other formats.
                if (b0 != 0xFF || (b1 and 0xE6) != 0xE2) return emptyList()
                val version = (b1 ushr 3) and 3
                val bitrateIdx = b2 ushr 4
                val rateIdx = (b2 ushr 2) and 3
                if (version == 1 || bitrateIdx !in 1..14 || rateIdx == 3) return emptyList()

                val rate = SAMPLE_RATES[version][rateIdx]
                val kbps = (if (version == 3) BITRATES_V1_L3 else BITRATES_V2_L3)[bitrateIdx]
                val coeff = if (version == 3) 144_000 else 72_000
                val frameSize = coeff * kbps / rate + ((b2 ushr 1) and 1)
                if (frameSize > remaining) break // Ignore an incomplete final frame.
                val frameNs = (if (version == 3) 1152L else 576L) * 1_000_000_000L / rate
                val mono = if (b3 ushr 6 == 3) 1 else 0
                val structureKey = (version shl 3) or (rateIdx shl 1) or mono
                structure.add(structureKey, elapsedNs, frameNs)
                bitrate.add((structureKey shl 4) or bitrateIdx, elapsedNs, frameNs)
                elapsedNs += frameNs
                skip(frameSize)
            }
            elapsedNs
        }
        val totalMs = elapsedNs / NS_PER_MS
        if (totalMs < 60_000L) return emptyList()
        val structuralSplices = structure.deviations(elapsedNs) ?: return emptyList()
        val candidates = structuralSplices + bitrate.deviations(elapsedNs).orEmpty()

        val merged = ArrayList<Segment>()
        for (segment in candidates.sortedBy { it.startMs }) {
            val previous = merged.lastOrNull()
            if (previous != null && segment.startMs - previous.endMs <= 500L) {
                merged[merged.lastIndex] = Segment(previous.startMs, maxOf(previous.endMs, segment.endMs))
            } else {
                merged.add(segment)
            }
        }
        return merged.mapNotNull { segment ->
            val edge = segment.startMs <= 5_000L || segment.endMs >= totalMs - 5_000L
            val maxLength = if (edge) MAX_EDGE_SPLICE_MS else MAX_MID_SPLICE_MS
            if (segment.endMs - segment.startMs in MIN_SPLICE_MS..maxLength) segment to 0.45f else null
        }
    }
}
