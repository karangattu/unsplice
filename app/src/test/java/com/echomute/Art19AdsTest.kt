package com.echomute

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Art19AdsTest {
    /** Trimmed from the real episode JSON for "#2111" of The Adam and Dr. Drew Show. */
    private val episodeJson = """
        {
          "ad_pods": [
            {"start_position": 0.0, "ads": [{"segments": [{"duration": 14.86}]}]},
            {"start_position": 956.34, "ads": [{"segments": [{"duration": 30.03}]}]},
            {"start_position": 1956.06, "ads": [{"segments": [{"duration": 30.03}]}]},
            {"start_position": null, "ads": [{"segments": [{"duration": 30.03}]}]}
          ],
          "content": {
            "duration": 2897.42367,
            "media": {
              "waveform_bin": {"url": "https://content.production.cdn.art19.com/episodes/05664478/waveform.dat"}
            }
          }
        }
    """.trimIndent()

    private val enclosureUrl = "https://dts.podtrac.com/redirect.mp3/prfx.byspotify.com/e/claritaspod.com/measure/arttrk.com/p/PDPN1/pscrb.fm/rss/p/rss.art19.com/episodes/05664478-169e-4414-b6cb-fcdc24be3794.mp3?rss_browser=abc"
    private val resolvedUrl = "https://rss.art19.com/external/episodes/05664478-169e-4414-b6cb-fcdc24be3794.mp3?rss_browser=abc"
    private val cdnRedirectUrl = "https://content.production.cdn.art19.com/validation=1794842438,763b7236-c7a7-5bf4-846f-867dc1de552c,29d2P59_4ce_sM5b5l6D537B5ac/episodes/05664478-169e-4414-b6cb-fcdc24be3794/1bce648739cb1cd01fa9d52d2568db55be43c32550d4cc3a9cfbbef761bf979f66dd3f4f15f8772e21686fc1d53ff9618b2bd42516e289b9d68e2386b0f5e84f/postfri.mp3"
    private val clipUrl = "https://rss.art19.com/episodes/05664478-169e-4414-b6cb-fcdc24be3794.json"

    @Test fun buildsTheClipUrlFromEitherAudioUrl() {
        assertEquals(clipUrl, art19ClipUrl(enclosureUrl))
        assertEquals(clipUrl, art19ClipUrl(resolvedUrl))
        assertEquals(clipUrl, art19ClipUrl(cdnRedirectUrl))
        assertNull(art19ClipUrl("https://example.com/episodes/05664478-169e-4414-b6cb-fcdc24be3794.mp3"))
    }

    @Test fun readsTheFixedAdSlotsAndTurnsThePostRollIntoAnEndMarker() {
        val info = requireNotNull(parseArt19Clip(episodeJson))
        assertEquals(listOf(0L, 956_340L, 1_956_060L, 2_897_423L), info.markersMs)
        assertEquals(2_897_423L, info.cleanDurationMs)
        assertEquals("https://content.production.cdn.art19.com/episodes/05664478/waveform.dat", info.waveformUrl)
    }

    @Test fun rejectsClipsWithoutPodsOrAWaveform() {
        assertNull(parseArt19Clip("""{"content":{"duration":100}}"""))
        assertNull(parseArt19Clip("""{"ad_pods":[{"start_position":1.0}],"content":{"duration":100}}"""))
        assertNull(parseArt19Clip("""{"ad_pods":[],"content":{"duration":100,"media":{"waveform_bin":{"url":"https://art19.com/w.dat"}}}}"""))
    }

    @Test fun parsesTheBbcWaveformBinaryIntoAPeakEnvelope() {
        // 8 min/max pairs at 16000 Hz / 1000 samples per pixel = 500 ms, one pair per 1/16 s bucket.
        val buffer = ByteBuffer.allocate(20 + 8 * 2).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(1).putInt(1).putInt(16000).putInt(1000).putInt(8)
        val pairs = listOf(10 to -20, 30 to -40, 5 to -50, 0 to 0, -60 to 60, 1 to -1, 100 to -100, 7 to 9)
        for ((lo, hi) in pairs) {
            buffer.put(lo.toByte()).put(hi.toByte())
        }
        val (rate, envelope) = parseArt19Waveform(buffer.array())
        assertEquals(ART19_ENVELOPE_RATE, rate)
        assertEquals(8, envelope.size)
        assertArrayEquals(intArrayOf(20, 40, 50, 0, 60, 1, 100, 9), envelope)
    }

    @Test fun alignsTheDownloadedAudioAgainstTheAdFreeWaveform() {
        val rate = ART19_ENVELOPE_RATE
        // A varied ad-free "show" of 600 s.
        val clean = IntArray(600 * rate) { i -> (kotlin.math.abs(kotlin.math.sin(i * 0.37)) * 90).toInt() + i % 7 }
        val adSeconds = intArrayOf(20, 30, 25, 15)
        val downloaded = ArrayList<Int>(clean.size + adSeconds.sum() * rate)
        fun addAd(seconds: Int) { repeat(seconds * rate) { downloaded.add(3) } }
        addAd(20)
        downloaded.addAll(clean.slice(0 until 100 * rate))
        addAd(30)
        downloaded.addAll(clean.slice(100 * rate until 300 * rate))
        addAd(25)
        downloaded.addAll(clean.slice(300 * rate until clean.size))
        addAd(15)

        val markers = listOf(0L, 100_000L, 300_000L, 600_000L)
        val breaks = alignAdBreaks(markers, clean, downloaded.toIntArray(), rate)
        assertEquals("All four pods should be found", 4, breaks.size)
        val expectedStarts = longArrayOf(0L, 120_000L, 350_000L, 675_000L)
        val expectedEnds = longArrayOf(20_000L, 150_000L, 375_000L, 690_000L)
        for (i in breaks.indices) {
            val segment = breaks[i].segment
            assertTrue("break $i starts at ${segment.startMs}", kotlin.math.abs(segment.startMs - expectedStarts[i]) < 1_000L)
            assertTrue("break $i ends at ${segment.endMs}", kotlin.math.abs(segment.endMs - expectedEnds[i]) < 1_000L)
            assertTrue("break $i match ${breaks[i].match}", breaks[i].match > 0.9)
        }
    }

    @Test fun resolvesArt19MarkersOnlyWhenAdsWereActuallyInserted() {
        val info = Art19AdInfo(listOf(0L, 956_340L), "https://art19.com/w.dat", 600_000L)
        val waveform: (String) -> Pair<Int, IntArray> = {
            ART19_ENVELOPE_RATE to IntArray(600 * ART19_ENVELOPE_RATE)
        }
        val target = requireNotNull(
            resolveArt19Target(
                resolvedUrl = resolvedUrl,
                probedDurationMs = 700_000L,
                clipFetcher = { info },
                waveformFetcher = waveform
            )
        )
        assertEquals(ART19_ENVELOPE_RATE, target.rate)
        assertEquals(600 * ART19_ENVELOPE_RATE, target.clean.size)

        assertNull(
            "A download as long as the ad-free episode has no stitched ads",
            resolveArt19Target(
                resolvedUrl = resolvedUrl,
                probedDurationMs = 601_000L,
                clipFetcher = { info },
                waveformFetcher = { throw AssertionError("waveform should not be fetched") }
            )
        )
        assertNull(
            "No pods means nothing to align",
            resolveArt19Target(
                resolvedUrl = resolvedUrl,
                probedDurationMs = 700_000L,
                clipFetcher = { info.copy(markersMs = emptyList()) },
                waveformFetcher = { throw AssertionError("waveform should not be fetched") }
            )
        )

        val candidateTarget = requireNotNull(
            resolveArt19Target(
                candidateUrls = listOf("https://cdn.example.com/opaque.mp3", enclosureUrl),
                probedDurationMs = 700_000L,
                clipFetcher = { url -> if (art19ClipUrl(url) != null) info else null },
                waveformFetcher = waveform
            )
        )
        assertEquals(ART19_ENVELOPE_RATE, candidateTarget.rate)
    }
}
