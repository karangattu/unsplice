package com.echomute

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class Mp3SpliceDetectorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private fun mpeg1L3Frame(bitrateIdx: Int, sampleRateIdx: Int = 0, channelMode: Int = 1): ByteArray {
        val bitrates = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        val rates = intArrayOf(44100, 48000, 32000, 0)
        val size = (144_000 * bitrates[bitrateIdx]) / rates[sampleRateIdx]
        val frame = ByteArray(size)
        frame[0] = 0xFF.toByte()
        frame[1] = 0xFB.toByte()
        frame[2] = ((bitrateIdx shl 4) or (sampleRateIdx shl 2)).toByte()
        frame[3] = (channelMode shl 6).toByte()
        return frame
    }

    private fun writeFrames(out: ByteArrayOutputStream, count: Int, bitrateIdx: Int, sampleRateIdx: Int = 0, channelMode: Int = 1) {
        val frame = mpeg1L3Frame(bitrateIdx, sampleRateIdx, channelMode)
        repeat(count) { out.write(frame) }
    }

    private fun id3v2Tag(payloadBytes: Int): ByteArray {
        val tag = ByteArray(10 + payloadBytes)
        tag[0] = 'I'.code.toByte()
        tag[1] = 'D'.code.toByte()
        tag[2] = '3'.code.toByte()
        tag[3] = 3
        tag[6] = ((payloadBytes ushr 21) and 0x7F).toByte()
        tag[7] = ((payloadBytes ushr 14) and 0x7F).toByte()
        tag[8] = ((payloadBytes ushr 7) and 0x7F).toByte()
        tag[9] = (payloadBytes and 0x7F).toByte()
        return tag
    }

    @Test
    fun detectsMidRollBitrateSpliceInCbrPodcast() {
        val out = ByteArrayOutputStream()
        out.write(id3v2Tag(64))
        // ~38.28 frames/sec at 44100 Hz; 383 frames ~= 10s, 1148 frames ~= 30s
        writeFrames(out, 2300, bitrateIdx = 9) // ~60s 128kbps
        writeFrames(out, 1150, bitrateIdx = 5) // ~30s 64kbps ad
        writeFrames(out, 2300, bitrateIdx = 9) // ~60s 128kbps

        val file: File = tempFolder.newFile("cbr_splice.mp3")
        file.writeBytes(out.toByteArray())

        val detected = Mp3SpliceDetector.detect(file, probedDurationMs = 150_000L, rssDurationMs = 120_000L)
        assertEquals(1, detected.size)
        val (seg, conf) = detected[0]
        assertTrue("startMs=${seg.startMs}", seg.startMs in 58_000L..62_000L)
        assertTrue("endMs=${seg.endMs}", seg.endMs in 88_000L..92_000L)
        assertTrue("conf=$conf", conf >= AUTO_SKIP_CONFIDENCE)
    }

    @Test
    fun ignoresVbrBitrateVariationWhileCatchingStructuralSplice() {
        val out = ByteArrayOutputStream()
        // 2300 frames of varying bitrates, all 44100Hz JointStereo
        for (i in 0 until 2300) {
            val brIdx = 7 + (i % 5) // 96..192 kbps
            out.write(mpeg1L3Frame(bitrateIdx = brIdx, sampleRateIdx = 0, channelMode = 1))
        }
        // 1150 frames (~30s) of Mono (channelMode = 3) ad
        writeFrames(out, 1150, bitrateIdx = 9, sampleRateIdx = 0, channelMode = 3)
        for (i in 0 until 2300) {
            val brIdx = 7 + (i % 5)
            out.write(mpeg1L3Frame(bitrateIdx = brIdx, sampleRateIdx = 0, channelMode = 1))
        }

        val file = tempFolder.newFile("vbr_struct.mp3")
        file.writeBytes(out.toByteArray())

        val detected = Mp3SpliceDetector.detect(file, probedDurationMs = 150_000L, rssDurationMs = 150_000L)
        assertEquals(1, detected.size)
        val (seg, conf) = detected[0]
        assertTrue(seg.startMs in 58_000L..62_000L)
        assertTrue(seg.endMs in 88_000L..92_000L)
        assertTrue(conf >= AUTO_SKIP_CONFIDENCE)
    }

    @Test
    fun uniformCbrFileProducesNoSplices() {
        val out = ByteArrayOutputStream()
        writeFrames(out, 3000, bitrateIdx = 9)
        val file = tempFolder.newFile("clean.mp3")
        file.writeBytes(out.toByteArray())

        assertTrue(Mp3SpliceDetector.detect(file, 78_000L, 78_000L).isEmpty())
    }

    @Test
    fun filtersShortGlitchRunsBelowMinimumDuration() {
        val out = ByteArrayOutputStream()
        writeFrames(out, 2300, bitrateIdx = 9)
        writeFrames(out, 100, bitrateIdx = 5) // ~2.6s glitch
        writeFrames(out, 2300, bitrateIdx = 9)
        val file = tempFolder.newFile("glitch.mp3")
        file.writeBytes(out.toByteArray())

        assertTrue(Mp3SpliceDetector.detect(file, 122_000L, 122_000L).isEmpty())
    }
}
