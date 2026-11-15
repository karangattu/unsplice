package com.echomute

import java.io.File
import java.io.RandomAccessFile

internal data class Mp3FrameProfile(
    val mpegVersion: Int,
    val sampleRate: Int,
    val channelMode: Int,
    val bitrateKbps: Int
) {
    val structuralKey: Long
        get() = (mpegVersion.toLong() shl 40) or (sampleRate.toLong() shl 8) or channelMode.toLong()

    val fullKey: Long
        get() = (structuralKey shl 16) or bitrateKbps.toLong()
}

internal data class Mp3FrameRun(
    val profile: Mp3FrameProfile,
    val startMs: Long,
    val endMs: Long
) {
    val durationMs: Long get() = endMs - startMs
}

object Mp3SpliceDetector {
    private val BITRATES_V1_L3 = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
    private val BITRATES_V2_L3 = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
    private val SAMPLE_RATES_V1 = intArrayOf(44100, 48000, 32000, 0)
    private val SAMPLE_RATES_V2 = intArrayOf(22050, 24000, 16000, 0)
    private val SAMPLE_RATES_V25 = intArrayOf(11025, 12000, 8000, 0)

    private const val MIN_SPLICE_MS = 12_000L
    private const val MAX_MID_SPLICE_MS = 210_000L
    private const val MAX_EDGE_SPLICE_MS = 240_000L
    private const val MERGE_GAP_MS = 3_000L
    private const val BUFFER_SIZE = 65_536

    internal fun detect(
        file: File,
        probedDurationMs: Long?,
        rssDurationMs: Long?
    ): List<Pair<Segment, Float>> {
        if (!file.isFile || file.length() < 4096L) return emptyList()
        val runs = scanFrameRuns(file)
        if (runs.size < 2) return emptyList()
        val daiShifted = probedDurationMs != null && rssDurationMs != null &&
            probedDurationMs > 0L && rssDurationMs > 0L &&
            probedDurationMs > rssDurationMs + 10_000L
        return scoreRuns(runs, daiShifted)
    }

    internal fun scanFrameRuns(file: File): List<Mp3FrameRun> {
        val fileLen = file.length()
        if (fileLen < 16L) return emptyList()
        RandomAccessFile(file, "r").use { raf ->
            var offset = skipId3v2(raf, fileLen)
            val totalEnd = fileLen - if (hasId3v1(raf, fileLen)) 128L else 0L
            if (offset >= totalEnd) return emptyList()

            val runs = ArrayList<Mp3FrameRun>()
            val header = ByteArray(4)
            var currentProfile: Mp3FrameProfile? = null
            var runStartUs = 0L
            var elapsedUs = 0L

            var buf = ByteArray(BUFFER_SIZE)
            var bufStart = offset
            raf.seek(offset)
            var bufLen = raf.read(buf).coerceAtLeast(0)
            var pos = 0

            fun ensureBytes(need: Int): Boolean {
                val avail = bufLen - pos
                if (avail >= need) return true
                val remaining = avail.coerceAtLeast(0)
                if (remaining > 0) System.arraycopy(buf, pos, buf, 0, remaining)
                bufStart += pos
                pos = 0
                bufLen = remaining
                while (bufLen < need && bufStart + bufLen < totalEnd) {
                    val toRead = minOf(buf.size - bufLen, (totalEnd - (bufStart + bufLen)).toInt())
                    val n = raf.read(buf, bufLen, toRead)
                    if (n <= 0) break
                    bufLen += n
                }
                return bufLen >= need
            }

            while (ensureBytes(4)) {
                val b0 = buf[pos].toInt() and 0xFF
                val b1 = buf[pos + 1].toInt() and 0xFF
                if (b0 != 0xFF || (b1 and 0xE0) != 0xE0) {
                    if (b0 == 'I'.code && b1 == 'D'.code && ensureBytes(10) &&
                        buf[pos + 2].toInt() == '3'.code
                    ) {
                        val tagSize = syncSafeInt(buf, pos + 6)
                        val skip = 10L + tagSize + if ((buf[pos + 5].toInt() and 0x10) != 0) 10L else 0L
                        val target = bufStart + pos + skip
                        if (target >= totalEnd) break
                        raf.seek(target)
                        bufStart = target
                        bufLen = 0
                        pos = 0
                        continue
                    }
                    pos++
                    continue
                }

                val b2 = buf[pos + 2].toInt() and 0xFF
                val b3 = buf[pos + 3].toInt() and 0xFF
                val parsed = parseHeader(b1, b2, b3)
                if (parsed == null) {
                    pos++
                    continue
                }
                val (profile, frameSize, frameUs) = parsed
                if (frameSize < 4 || bufStart + pos + frameSize > totalEnd) break

                if (currentProfile == null) {
                    currentProfile = profile
                    runStartUs = elapsedUs
                } else if (profile != currentProfile) {
                    val runEndMs = elapsedUs / 1000L
                    val runStartMs = runStartUs / 1000L
                    if (runEndMs > runStartMs) {
                        runs.add(Mp3FrameRun(currentProfile, runStartMs, runEndMs))
                    }
                    currentProfile = profile
                    runStartUs = elapsedUs
                }

                elapsedUs += frameUs
                val nextAbs = bufStart + pos + frameSize
                if (pos + frameSize <= bufLen) {
                    pos += frameSize
                } else {
                    raf.seek(nextAbs)
                    bufStart = nextAbs
                    bufLen = 0
                    pos = 0
                }
            }

            if (currentProfile != null) {
                val endMs = elapsedUs / 1000L
                val startMs = runStartUs / 1000L
                if (endMs > startMs) {
                    runs.add(Mp3FrameRun(currentProfile, startMs, endMs))
                }
            }
            return runs
        }
    }

    internal fun scoreRuns(runs: List<Mp3FrameRun>, daiShifted: Boolean): List<Pair<Segment, Float>> {
        if (runs.size < 2) return emptyList()
        val totalMs = runs.sumOf { it.durationMs }
        if (totalMs < 60_000L) return emptyList()

        val structuralTotals = HashMap<Long, Long>()
        val fullTotals = HashMap<Long, Long>()
        for (r in runs) {
            structuralTotals[r.profile.structuralKey] = (structuralTotals[r.profile.structuralKey] ?: 0L) + r.durationMs
            fullTotals[r.profile.fullKey] = (fullTotals[r.profile.fullKey] ?: 0L) + r.durationMs
        }

        val dominantFull = fullTotals.maxByOrNull { it.value } ?: return emptyList()
        val dominantStruct = structuralTotals.maxByOrNull { it.value } ?: return emptyList()
        val cbrRatio = dominantFull.value.toDouble() / totalMs.toDouble()
        val structRatio = dominantStruct.value.toDouble() / totalMs.toDouble()
        val useCbr = cbrRatio >= 0.70

        if (!useCbr && structRatio < 0.70) return emptyList()

        val rawCandidates = ArrayList<Pair<Segment, Boolean>>()
        for (r in runs) {
            if (r.durationMs < 500L) continue
            val isStructuralDiff = r.profile.structuralKey != dominantStruct.key
            val isBitrateDiff = useCbr && r.profile.fullKey != dominantFull.key
            if (!isStructuralDiff && !isBitrateDiff) continue
            rawCandidates.add(Segment(r.startMs, r.endMs) to isStructuralDiff)
        }
        if (rawCandidates.isEmpty()) return emptyList()

        val merged = ArrayList<Pair<Segment, Boolean>>()
        for ((seg, structDiff) in rawCandidates) {
            val prev = merged.lastOrNull()
            if (prev != null && seg.startMs - prev.first.endMs <= MERGE_GAP_MS) {
                merged[merged.lastIndex] = Segment(prev.first.startMs, seg.endMs) to (prev.second || structDiff)
            } else {
                merged.add(seg to structDiff)
            }
        }

        val out = ArrayList<Pair<Segment, Float>>()
        for ((seg, structDiff) in merged) {
            val len = seg.endMs - seg.startMs
            val isEdge = seg.startMs <= 5_000L || seg.endMs >= totalMs - 5_000L
            val maxLen = if (isEdge) MAX_EDGE_SPLICE_MS else MAX_MID_SPLICE_MS
            if (len < MIN_SPLICE_MS || len > maxLen) continue
            val highConfidence = daiShifted ||
                (structDiff && structRatio >= 0.65) ||
                (useCbr && cbrRatio >= 0.80)
            out.add(seg to if (highConfidence) 0.75f else 0.45f)
        }
        return out
    }

    private data class ParsedHeader(val profile: Mp3FrameProfile, val frameSize: Int, val frameUs: Long)

    private fun parseHeader(b1: Int, b2: Int, b3: Int): ParsedHeader? {
        val versionBits = (b1 ushr 3) and 0x3
        val layerBits = (b1 ushr 1) and 0x3
        if (versionBits == 1 || layerBits != 1) return null

        val bitrateIdx = (b2 ushr 4) and 0xF
        val sampleRateIdx = (b2 ushr 2) and 0x3
        if (bitrateIdx == 0 || bitrateIdx == 15 || sampleRateIdx == 3) return null

        val padding = (b2 ushr 1) and 0x1
        val channelMode = (b3 ushr 6) and 0x3

        val bitrateKbps = if (versionBits == 3) BITRATES_V1_L3[bitrateIdx] else BITRATES_V2_L3[bitrateIdx]
        val sampleRate = when (versionBits) {
            3 -> SAMPLE_RATES_V1[sampleRateIdx]
            2 -> SAMPLE_RATES_V2[sampleRateIdx]
            else -> SAMPLE_RATES_V25[sampleRateIdx]
        }
        if (bitrateKbps <= 0 || sampleRate <= 0) return null

        val coeff = if (versionBits == 3) 144_000 else 72_000
        val samplesPerFrame = if (versionBits == 3) 1152L else 576L
        val frameSize = (coeff * bitrateKbps) / sampleRate + padding
        val frameUs = (samplesPerFrame * 1_000_000L) / sampleRate
        return ParsedHeader(Mp3FrameProfile(versionBits, sampleRate, channelMode, bitrateKbps), frameSize, frameUs)
    }

    private fun skipId3v2(raf: RandomAccessFile, fileLen: Long): Long {
        if (fileLen < 10L) return 0L
        val head = ByteArray(10)
        raf.seek(0L)
        if (raf.read(head) < 10) return 0L
        if (head[0].toInt() != 'I'.code || head[1].toInt() != 'D'.code || head[2].toInt() != '3'.code) {
            return 0L
        }
        val size = syncSafeInt(head, 6)
        val footer = if ((head[5].toInt() and 0x10) != 0) 10L else 0L
        return (10L + size + footer).coerceAtMost(fileLen)
    }

    private fun hasId3v1(raf: RandomAccessFile, fileLen: Long): Boolean {
        if (fileLen < 128L) return false
        val tag = ByteArray(3)
        raf.seek(fileLen - 128L)
        if (raf.read(tag) < 3) return false
        return tag[0].toInt() == 'T'.code && tag[1].toInt() == 'A'.code && tag[2].toInt() == 'G'.code
    }

    private fun syncSafeInt(buf: ByteArray, offset: Int): Int {
        return ((buf[offset].toInt() and 0x7F) shl 21) or
            ((buf[offset + 1].toInt() and 0x7F) shl 14) or
            ((buf[offset + 2].toInt() and 0x7F) shl 7) or
            (buf[offset + 3].toInt() and 0x7F)
    }
}
