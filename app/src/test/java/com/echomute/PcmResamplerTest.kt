package com.echomute

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PcmResamplerTest {
    @Test fun averagesStereoAndDownsamplesAcrossBuffers() {
        val resampler = PcmResampler(16000, 2)
        assertArrayEquals(shortArrayOf(2000), resampler.convert(shortArrayOf(1000, 3000, 2000, 2000, 3000, 5000)))
        assertArrayEquals(shortArrayOf(4000), resampler.convert(shortArrayOf(4000, 4000)))
    }
    @Test fun preservesEightKhzMono() {
        val resampler = PcmResampler(8000, 1)
        assertArrayEquals(shortArrayOf(-100, 200, 300), resampler.convert(shortArrayOf(-100, 200, 300)))
    }
    @Test fun downsamplesToSixteenKhz() {
        val resampler = PcmResampler(48000, 1, 16000)
        assertArrayEquals(shortArrayOf(200, 500), resampler.convert(shortArrayOf(100, 200, 300, 400, 500, 600)))
    }
    @Test fun convertToBytesProducesEquivalentLittleEndianBytesForStereo() {
        val input = shortArrayOf(1000, 3000, 2000, 2000, 3000, 5000)
        val r1 = PcmResampler(16000, 2)
        val r2 = PcmResampler(16000, 2)
        val shorts = r1.convert(input)
        val expectedBytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            expectedBytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            expectedBytes[i * 2 + 1] = ((shorts[i].toInt() ushr 8) and 0xFF).toByte()
        }
        val actualBytes = ByteArray(64)
        val written = r2.convertToBytes(input, input.size, actualBytes, 0)
        assertEquals(expectedBytes.size, written)
        assertArrayEquals(expectedBytes, actualBytes.copyOf(written))
    }
    @Test fun convertToBytesProducesEquivalentLittleEndianBytesForMono() {
        val input = shortArrayOf(-500, 1200, -3200, 15000, -20000, 8000)
        val r1 = PcmResampler(48000, 1, 16000)
        val r2 = PcmResampler(48000, 1, 16000)
        val shorts = r1.convert(input)
        val expectedBytes = ByteArray(shorts.size * 2)
        for (i in shorts.indices) {
            expectedBytes[i * 2] = (shorts[i].toInt() and 0xFF).toByte()
            expectedBytes[i * 2 + 1] = ((shorts[i].toInt() ushr 8) and 0xFF).toByte()
        }
        val actualBytes = ByteArray(64)
        val written = r2.convertToBytes(input, input.size, actualBytes, 0)
        assertEquals(expectedBytes.size, written)
        assertArrayEquals(expectedBytes, actualBytes.copyOf(written))
    }
}
