package com.echomute

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AudioDecoderTest {
    @Test fun decodesMp3ToEightKhzMonoPcm() {
        val testContext = InstrumentationRegistry.getInstrumentation().context
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val source = File(dir, "decoder-test.mp3")
        val pcm = File(dir, "decoder-test.pcm")
        testContext.assets.open("tone.mp3").use { input -> source.outputStream().use { input.copyTo(it) } }
        try {
            decodeAudio(source, pcm)
            assertTrue("Expected roughly two seconds of 8 kHz 16-bit PCM", pcm.length() in 30_000..35_000)
            assertTrue("Decoded audio should not be silent", pcm.readBytes().any { it.toInt() != 0 })
        } finally { source.delete(); pcm.delete() }
    }
}
