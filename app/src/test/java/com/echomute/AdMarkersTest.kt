package com.echomute

import java.io.File
import java.util.Base64
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class AdMarkersTest {
    @Test fun findsOmnyClipBehindRedirects() {
        val url = "https://podtrac.com/pts/redirect.mp3/traffic.omny.fm/d/clips/98a2599f-cd9b-4f2a-9be4-b22c00e585f2/cf386c8a-37eb-415e-abd5-b32f0124a981/cdaf596b-aad0-4452-80ad-b4f001425984/audio.mp3?utm_source=Podcast"
        assertEquals(OmnyClip("98a2599f-cd9b-4f2a-9be4-b22c00e585f2", "cdaf596b-aad0-4452-80ad-b4f001425984"), omnyClip(url))
        assertNull(omnyClip("https://example.org/episode.mp3"))
    }

    @Test fun parsesClipMarkersAndWaveform() {
        val clip = """{"WaveformUrl":"https://www.omnycontent.com/waveform","AdMarkers":[{"Offset":"00:18:17","AdMarkerType":"MidRoll"},{"Offset":"00:00:00","AdMarkerType":"PreRoll"},{"Offset":"02:37:05.1890000","AdMarkerType":"PostRoll"}]}"""
        assertEquals(OmnyAdInfo(listOf(0L, 1_097_000L, 9_425_189L), "https://www.omnycontent.com/waveform"), parseOmnyClip(clip))
        assertNull(parseOmnyClip("""{"WaveformUrl":"https://example.org/w","AdMarkers":[]}"""))
        assertNull(parseOmnyClip("""{"AdMarkers":[{"Offset":"00:00:00"}]}"""))

        val data = Base64.getEncoder().encodeToString(byteArrayOf(0, 16, 112, -1))
        val (rate, values) = parseOmnyWaveform("""{"SR":5,"SPP":0,"D":["$data"]}""")
        assertEquals(5, rate)
        assertArrayEquals(intArrayOf(0, 16, 112, 255), values)
    }

    /** Builds a download by inserting breaks of random audio into a random ad-free envelope. */
    private fun insertBreaks(clean: IntArray, breaks: List<Pair<Int, Int>>, random: Random): IntArray {
        val out = ArrayList<Int>()
        var from = 0
        for ((at, length) in breaks) {
            for (i in from until at) out.add(clean[i])
            repeat(length) { out.add(random.nextInt(0, 120)) }
            from = at
        }
        for (i in from until clean.size) out.add(clean[i])
        return out.toIntArray()
    }

    @Test fun alignsPreMidAndPostRollBreaks() {
        val random = Random(1)
        val rate = 5
        val clean = IntArray(3600 * rate) { random.nextInt(0, 120) }
        // Pre-roll 183 s, mid-rolls 243 s and 258 s, post-roll 161 s.
        val downloaded = insertBreaks(clean, listOf(0 to 183 * rate, 1097 * rate to 243 * rate, 2644 * rate to 258 * rate, clean.size to 161 * rate), random)
        val breaks = alignAdBreaks(listOf(0L, 1_097_000L, 2_644_000L, 3_600_000L), clean, downloaded, rate)
        assertEquals(
            listOf(
                Segment(0, 183_000),
                Segment(1_280_000, 1_523_000),
                Segment(3_070_000, 3_328_000),
                Segment(4_284_000, 4_445_000),
            ),
            breaks.map { it.segment },
        )
        assertTrue(breaks.all { it.match > 0.9 })
    }

    @Test fun findsNothingWhenNoAdsWereInserted() {
        val random = Random(2)
        val clean = IntArray(600 * 5) { random.nextInt(0, 120) }
        assertTrue(alignAdBreaks(listOf(0L, 300_000L), clean, clean.copyOf(), 5).isEmpty())
    }

    @Test fun skipsMarkersWhereNoBreakWasInserted() {
        val random = Random(3)
        val rate = 5
        val clean = IntArray(1200 * rate) { random.nextInt(0, 120) }
        val downloaded = insertBreaks(clean, listOf(600 * rate to 120 * rate), random)
        assertEquals(listOf(Segment(600_000, 720_000)), alignAdBreaks(listOf(0L, 300_000L, 600_000L), clean, downloaded, rate).map { it.segment })
    }

    @Test fun envelopeTakesPeakLevelPerStep() {
        val pcm = File.createTempFile("envelope", ".pcm")
        try {
            val samples = ShortArray(8000 * 2) { if (it == 100) (-32768).toShort() else if (it == 2000) 500 else 10 }
            pcm.writeBytes(ByteArray(samples.size * 2) { i -> (samples[i / 2].toInt() shr (8 * (i % 2))).toByte() })
            val envelope = pcmEnvelope(pcm, 5)
            assertEquals(10, envelope.size)
            assertEquals(32768, envelope[0])
            assertEquals(500, envelope[1])
            assertEquals(10, envelope[2])
        } finally { pcm.delete() }
    }
}
