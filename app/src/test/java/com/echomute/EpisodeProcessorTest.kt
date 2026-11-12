package com.echomute

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeProcessorTest {
    private class FakeSegmentDao : LibraryDao {
        val stored = mutableListOf<AdSegment>()
        override suspend fun savePodcast(podcast: Podcast) {}
        override suspend fun setArtworkIfMissing(id: String, url: String) {}
        override suspend fun saveEpisode(episode: Episode) {}
        override suspend fun saveSegments(segments: List<AdSegment>) { stored.addAll(segments) }
        override fun observePodcasts(): Flow<List<Podcast>> = flowOf(emptyList())
        override suspend fun podcast(id: String): Podcast? = null
        override fun observeEpisodes(id: String): Flow<List<Episode>> = flowOf(emptyList())
        override fun observeAllEpisodes(): Flow<List<Episode>> = flowOf(emptyList())
        override suspend fun episodesForPodcast(id: String): List<Episode> = emptyList()
        override suspend fun episodesWithFingerprints(): List<Episode> = emptyList()
        override fun observeDownloads(): Flow<List<EpisodeFile>> = flowOf(emptyList())
        override suspend fun savePosition(id: String, positionMs: Long, durationMs: Long?) {}
        override suspend fun markFinished(id: String) {}
        override suspend fun setFinished(id: String, finished: Boolean) {}
        override suspend fun setStarred(id: String, starred: Boolean) {}
        override suspend fun episode(id: String): Episode? = null
        override suspend fun segments(id: String): List<AdSegment> = stored.filter { it.episodeId == id }.sortedBy { it.startMs }
        override fun observeAutoSkip(episodeId: String): Flow<Boolean?> = flowOf(null)
        override fun observeSegments(id: String): Flow<List<AdSegment>> = flowOf(emptyList())
        override fun observePodcastSegments(podcastId: String): Flow<List<AdSegment>> = flowOf(emptyList())
        override suspend fun deleteSegments(id: String) { stored.removeAll { it.episodeId == id } }
        override suspend fun deleteSegment(id: Long) { stored.removeAll { it.id == id } }
        override suspend fun deletePodcast(id: String) {}
        override suspend fun deleteEpisode(id: String) {}
    }

    private fun int32Bytes(v: Int): ByteArray = byteArrayOf(
        (v ushr 24).toByte(),
        (v ushr 16 and 0xFF).toByte(),
        (v ushr 8 and 0xFF).toByte(),
        (v and 0xFF).toByte()
    )

    private fun syncsafeBytes(v: Int): ByteArray = byteArrayOf(
        (v ushr 21 and 0x7F).toByte(),
        (v ushr 14 and 0x7F).toByte(),
        (v ushr 7 and 0x7F).toByte(),
        (v and 0x7F).toByte()
    )

    private fun id3Frame(id: String, body: ByteArray, version: Int = 4): ByteArray = ByteArrayOutputStream().apply {
        write(id.toByteArray())
        write(if (version == 4) syncsafeBytes(body.size) else int32Bytes(body.size))
        write(0)
        write(0)
        write(body)
    }.toByteArray()

    private fun id3ChapFrame(id: String, startMs: Int, endMs: Int, title: String): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write(id.toByteArray())
            write(0)
            write(int32Bytes(startMs))
            write(int32Bytes(endMs))
            write(int32Bytes(-1))
            write(int32Bytes(-1))
            write(id3Frame("TIT2", byteArrayOf(3) + title.toByteArray(Charsets.UTF_8)))
        }.toByteArray()
        return id3Frame("CHAP", body)
    }

    private fun id3Tag(vararg frames: ByteArray): ByteArray {
        val content = frames.fold(ByteArray(0)) { a, b -> a + b } + ByteArray(16)
        return ByteArrayOutputStream().apply {
            write("ID3".toByteArray())
            write(4)
            write(0)
            write(0)
            write(syncsafeBytes(content.size))
            write(content)
        }.toByteArray() + ByteArray(64)
    }

    @Test fun packedFingerprintsRoundTripAndMatchTwelveByteBinaryFormat() {
        val file = File.createTempFile("fp-roundtrip", ".bin")
        try {
            val expected = longArrayOf(
                packFingerprint(-1, 0L),
                packFingerprint(123456, 45_128L),
                packFingerprint(Int.MAX_VALUE, 3_600_999L)
            )
            writePackedFingerprints(file, expected)
            assertEquals(expected.size * 12L, file.length())
            assertArrayEquals(expected, readPackedFingerprints(file))

            val decoded = DataInputStream(file.inputStream().buffered()).use { input ->
                List(expected.size) { Fingerprint(input.readInt(), input.readLong()) }
            }
            assertEquals(
                listOf(
                    Fingerprint(-1, 0L),
                    Fingerprint(123456, 45_128L),
                    Fingerprint(Int.MAX_VALUE, 3_600_999L)
                ),
                decoded
            )
        } finally {
            file.delete()
        }
    }

    @Test fun candidateSelectionPrioritizesSamePodcastAndBoundsLibrarySize() {
        val current = Episode("cur", "pod-a", "Current", "https://example.com/cur.mp3", publishedAt = 1000L)
        val sameShow = (1..30).map { i ->
            Episode("a-$i", "pod-a", "A$i", "https://example.com/a$i.mp3", fingerprintPath = "/tmp/a$i.bin", publishedAt = i.toLong())
        }
        val otherShow = (1..25).map { i ->
            Episode("b-$i", "pod-b", "B$i", "https://example.com/b$i.mp3", fingerprintPath = "/tmp/b$i.bin", publishedAt = (i * 10).toLong())
        }
        val noFp = Episode("no-fp", "pod-a", "NoFP", "https://example.com/nofp.mp3", fingerprintPath = null, publishedAt = 9999L)

        val candidates = selectComparisonCandidates(current, sameShow + otherShow + listOf(current, noFp))
        assertEquals(40, candidates.size)
        assertEquals((30 downTo 7).map { "a-$it" }, candidates.take(24).map { it.id })
        assertEquals((25 downTo 10).map { "b-$it" }, candidates.drop(24).map { it.id })
    }

    @Test fun trustsDurationMatchedJsonAndPscChaptersAndSkipsDecode() {
        val audio = File.createTempFile("plain", ".mp3").apply { writeBytes(ByteArray(128)) }
        try {
            val jsonChapters = listOf(
                Chapter(0L, 60_000L, "Intro"),
                Chapter(60_000L, 120_000L, "Sponsor: Acme"),
                Chapter(120_000L, 180_000L, "Acme", toc = false, url = "https://acme.com/pod?utm_source=show")
            )
            val epWithJson = Episode(
                "ep-json",
                "pod",
                "Ep",
                "https://example.com/ep.mp3",
                chaptersUrl = "https://example.com/chapters.json",
                rssDurationMs = 1_800_000L
            )
            val resJson = resolveChaptersAndMetadata(epWithJson, audio, 1_802_000L, 1_800_000L) { jsonChapters }
            assertTrue(resJson.skipAudioDecode)
            assertEquals(2, resJson.immediateSegments.size)
            assertEquals(0.85f, resJson.immediateSegments[0].second, 0.0001f)
            assertEquals(0.9f, resJson.immediateSegments[1].second, 0.0001f)
            assertTrue(resJson.deferredSegments.isEmpty())

            val pscJson = encodeChaptersJson(listOf(Chapter(0L, null, "Intro"), Chapter(90_000L, 150_000L, "Ad break")))
            val epWithPsc = Episode(
                "ep-psc",
                "pod",
                "Ep",
                "https://example.com/ep.mp3",
                rssDurationMs = 1_800_000L,
                pscChaptersJson = pscJson
            )
            val resPsc = resolveChaptersAndMetadata(epWithPsc, audio, 1_800_000L, 1_800_000L)
            assertTrue(resPsc.skipAudioDecode)
            assertEquals(listOf(Segment(90_000L, 150_000L) to 0.85f), resPsc.immediateSegments)
        } finally {
            audio.delete()
        }
    }

    @Test fun downgradesDaiShiftedAndUnverifiedJsonChaptersBelowSkipDecode() {
        val audio = File.createTempFile("plain-dai", ".mp3").apply { writeBytes(ByteArray(128)) }
        try {
            val jsonChapters = listOf(
                Chapter(0L, 60_000L, "Intro"),
                Chapter(60_000L, 120_000L, "Ad break", toc = false, url = "https://acme.com/sponsor")
            )
            val ep = Episode(
                "ep-dai",
                "pod",
                "Ep",
                "https://example.com/ep.mp3",
                chaptersUrl = "https://example.com/chapters.json",
                rssDurationMs = 1_800_000L
            )
            val shifted = resolveChaptersAndMetadata(ep, audio, 1_860_000L, 1_800_000L) { jsonChapters }
            assertFalse(shifted.skipAudioDecode)
            assertTrue(shifted.immediateSegments.isEmpty())
            assertEquals(1, shifted.deferredSegments.size)
            assertTrue(shifted.deferredSegments[0].second < AUTO_SKIP_CONFIDENCE)

            val unverified = resolveChaptersAndMetadata(ep, audio, null, 1_800_000L) { jsonChapters }
            assertFalse(unverified.skipAudioDecode)
            assertTrue(unverified.immediateSegments.isEmpty())
            assertEquals(0.7f, unverified.deferredSegments[0].second, 0.0001f)
        } finally {
            audio.delete()
        }
    }

    @Test fun usesShowNoteChaptersOnlyWhenNoStructuredChaptersExist() {
        val audio = File.createTempFile("shownotes", ".mp3").apply { writeBytes(ByteArray(128)) }
        try {
            val description = "00:00 Intro\n05:00 Sponsor: Acme\n07:00 Deep Dive"
            val epOnlyNotes = Episode("ep-notes", "pod", "Ep", "https://example.com/ep.mp3", description = description, rssDurationMs = 1_800_000L)
            val resNotes = resolveChaptersAndMetadata(epOnlyNotes, audio, 1_800_000L, 1_800_000L)
            assertFalse(resNotes.skipAudioDecode)
            assertEquals(3, resNotes.chapters.size)
            assertEquals(1, resNotes.deferredSegments.size)
            assertEquals(Segment(300_000L, 420_000L), resNotes.deferredSegments[0].first)
            assertTrue(resNotes.deferredSegments[0].second < AUTO_SKIP_CONFIDENCE)

            val pscJson = encodeChaptersJson(listOf(Chapter(0L, 600_000L, "Main discussion")))
            val epWithPsc = epOnlyNotes.copy(pscChaptersJson = pscJson)
            val resWithPsc = resolveChaptersAndMetadata(epWithPsc, audio, 1_800_000L, 1_800_000L)
            assertFalse(resWithPsc.skipAudioDecode)
            assertTrue(resWithPsc.deferredSegments.isEmpty())
            assertEquals(1, resWithPsc.chapters.size)
        } finally {
            audio.delete()
        }
    }

    @Test fun handlesId3ChapAndAcastAdBreakCues() {
        val chapFile = File.createTempFile("id3-chap", ".mp3")
        val acastFile = File.createTempFile("id3-acast", ".mp3")
        try {
            chapFile.writeBytes(id3Tag(id3ChapFrame("c0", 0, 30_000, "Intro"), id3ChapFrame("c1", 30_000, 90_000, "Mid-roll ad")))
            val ep = Episode("ep-id3", "pod", "Ep", "https://example.com/ep.mp3")
            val chapRes = resolveChaptersAndMetadata(ep, chapFile, 1_000_000L, null)
            assertTrue(chapRes.skipAudioDecode)
            assertEquals(listOf(Segment(30_000L, 90_000L) to 0.9f), chapRes.immediateSegments)

            val txxxBody = ByteArrayOutputStream().apply {
                write(3)
                write("AIS_AD_BREAK_1".toByteArray())
                write(0)
                write("368648,0".toByteArray())
            }.toByteArray()
            acastFile.writeBytes(id3Tag(id3Frame("TXXX", txxxBody)))
            val acastRes = resolveChaptersAndMetadata(ep, acastFile, 1_000_000L, null)
            assertFalse(acastRes.skipAudioDecode)
            assertTrue(acastRes.immediateSegments.isEmpty())
            assertEquals(listOf(Segment(368_648L, 398_648L) to 0.4f), acastRes.cueFallbackSegments)
        } finally {
            chapFile.delete()
            acastFile.delete()
        }
    }

    @Test fun resolvesOmnyBehindRedirectAndChecksMarkersAndDuration() {
        MockWebServer().use { server ->
            val redirectedPath = "/d/clips/98a2599f-cd9b-4f2a-9be4-b22c00e585f2/cf386c8a-37eb-415e-abd5-b32f0124a981/cdaf596b-aad0-4452-80ad-b4f001425984/audio.mp3"
            val targetUrl = server.url(redirectedPath).toString()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", targetUrl))
            server.enqueue(MockResponse().setBody("mp3-bytes"))
            val dest = File.createTempFile("omny-redirect", ".part")
            try {
                val dl = downloadAudioWithUrl(server.url("/podtrac/ep.mp3").toString(), dest)
                assertEquals(targetUrl, dl.finalUrl)
                val omnyCdnUrl = "https://www.omnycontent.com$redirectedPath"
                assertNotNull(omnyClip(omnyCdnUrl))

                val markersTarget = resolveOmnyTarget(
                    resolvedUrl = omnyCdnUrl,
                    probedDurationMs = 700_000L,
                    clipFetcher = { OmnyAdInfo(listOf(100_000L), "https://omny.fm/w", durationMs = 600_000L) },
                    waveformFetcher = { 5 to IntArray(600 * 5) }
                )
                assertNotNull(markersTarget)
                assertEquals(5, markersTarget!!.rate)

                val noAdsInserted = resolveOmnyTarget(
                    resolvedUrl = omnyCdnUrl,
                    probedDurationMs = 601_000L,
                    clipFetcher = { OmnyAdInfo(listOf(100_000L), "https://omny.fm/w", durationMs = 600_000L) },
                    waveformFetcher = { throw AssertionError("waveform should not be fetched when no ads inserted") }
                )
                assertNull(noAdsInserted)

                val zeroMarkers = resolveOmnyTarget(
                    resolvedUrl = omnyCdnUrl,
                    probedDurationMs = 700_000L,
                    clipFetcher = { OmnyAdInfo(emptyList(), "https://omny.fm/w", durationMs = 600_000L) },
                    waveformFetcher = { throw AssertionError("waveform should not be fetched when hasMarkers is false") }
                )
                assertNull(zeroMarkers)
            } finally {
                dest.delete()
                File(dest.path + ".etag").delete()
            }
        }
    }

    @Test fun saveNewSegmentsScoredRespectsPriorityAndSkipsOverlaps() = runBlocking {
        val dao = FakeSegmentDao()
        saveNewSegmentsScored(dao, "ep-1", listOf(Segment(10_000L, 40_000L) to 0.6f), "repeat")
        saveNewSegmentsScored(
            dao,
            "ep-1",
            listOf(
                Segment(15_000L, 35_000L) to TRANSCRIPT_CONFIDENCE,
                Segment(100_000L, 140_000L) to TRANSCRIPT_CONFIDENCE
            ),
            "transcript"
        )
        val saved = dao.segments("ep-1")
        assertEquals(2, saved.size)
        assertEquals("repeat", saved[0].source)
        assertEquals(10_000L, saved[0].startMs)
        assertEquals("transcript", saved[1].source)
        assertEquals(100_000L, saved[1].startMs)
        assertEquals(TRANSCRIPT_CONFIDENCE, saved[1].confidence, 0.0001f)
    }
}
