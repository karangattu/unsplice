package com.echomute

import org.junit.Assert.assertArrayEquals
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
}
