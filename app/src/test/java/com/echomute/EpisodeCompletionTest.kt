package com.echomute

import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeCompletionTest {

    private class TestDao : LibraryDao {
        val podcasts = mutableMapOf<String, Podcast>()
        val episodes = mutableMapOf<String, Episode>()
        val segmentsList = mutableListOf<AdSegment>()

        override suspend fun savePodcast(podcast: Podcast) { podcasts[podcast.id] = podcast }
        override suspend fun setArtworkIfMissing(id: String, url: String) {
            podcasts[id]?.takeIf { it.artworkUrl == null }?.let { podcasts[id] = it.copy(artworkUrl = url) }
        }
        override suspend fun saveEpisode(episode: Episode) { episodes[episode.id] = episode }
        override suspend fun saveSegments(segments: List<AdSegment>) { segmentsList.addAll(segments) }
        override fun observePodcasts(): Flow<List<Podcast>> = flowOf(podcasts.values.toList())
        override suspend fun podcast(id: String): Podcast? = podcasts[id]
        override fun observeEpisodes(id: String): Flow<List<Episode>> = flowOf(episodes.values.filter { it.podcastId == id })
        override fun observeAllEpisodes(): Flow<List<Episode>> = flowOf(episodes.values.toList())
        override suspend fun episodesForPodcast(id: String): List<Episode> = episodes.values.filter { it.podcastId == id }
        override suspend fun episodesWithFingerprints(): List<Episode> = episodes.values.filter { it.fingerprintPath != null }
        override fun observeDownloads(): Flow<List<EpisodeFile>> = flowOf(episodes.values.mapNotNull { e -> e.localPath?.let { EpisodeFile(e.id, it) } })
        override suspend fun savePosition(id: String, positionMs: Long, durationMs: Long?) {
            episodes[id]?.let { episodes[id] = it.copy(positionMs = positionMs, durationMs = durationMs, finished = false) }
        }
        override suspend fun markFinished(id: String) {
            episodes[id]?.let { episodes[id] = it.copy(positionMs = 0, finished = true) }
        }
        override suspend fun setFinished(id: String, finished: Boolean) {
            episodes[id]?.let { episodes[id] = it.copy(positionMs = 0, finished = finished) }
        }
        override suspend fun setStarred(id: String, starred: Boolean) {
            episodes[id]?.let { episodes[id] = it.copy(starred = starred) }
        }
        override suspend fun episode(id: String): Episode? = episodes[id]
        override suspend fun segments(id: String): List<AdSegment> = segmentsList.filter { it.episodeId == id }
        override fun observeAutoSkip(episodeId: String): Flow<Boolean?> = flowOf(null)
        override fun observeSegments(id: String): Flow<List<AdSegment>> = flowOf(segmentsList.filter { it.episodeId == id })
        override fun observePodcastSegments(podcastId: String): Flow<List<AdSegment>> = flowOf(emptyList())
        override suspend fun deleteSegments(id: String) { segmentsList.removeAll { it.episodeId == id } }
        override suspend fun deleteSegment(id: Long) { segmentsList.removeAll { it.id == id } }
        override suspend fun deletePodcast(id: String) { podcasts.remove(id) }
        override suspend fun deleteEpisode(id: String) { episodes.remove(id) }
    }

    @Test
    fun completionThresholdDetectsPastNinetyFivePercent() {
        val duration = 1_000_000L
        assertFalse(isEpisodeCompleted(0L, duration))
        assertFalse(isEpisodeCompleted(500_000L, duration))
        assertFalse(isEpisodeCompleted(900_000L, duration))
        assertFalse(isEpisodeCompleted(949_000L, duration))
        assertTrue(isEpisodeCompleted(950_000L, duration))
        assertTrue(isEpisodeCompleted(960_000L, duration))
        assertTrue(isEpisodeCompleted(1_000_000L, duration))
        assertTrue(isEpisodeCompleted(1_050_000L, duration))
    }

    @Test
    fun completionThresholdDetectsWithinLastThirtySeconds() {
        val duration = 120_000L
        assertFalse(isEpisodeCompleted(80_000L, duration))
        assertFalse(isEpisodeCompleted(89_000L, duration))
        assertTrue(isEpisodeCompleted(90_000L, duration))
        assertTrue(isEpisodeCompleted(95_000L, duration))
        assertTrue(isEpisodeCompleted(100_000L, duration))
        assertTrue(isEpisodeCompleted(115_000L, duration))
    }

    @Test
    fun completionThresholdRejectsInvalidOrZeroInput() {
        assertFalse(isEpisodeCompleted(0L, 0L))
        assertFalse(isEpisodeCompleted(100L, 0L))
        assertFalse(isEpisodeCompleted(100L, null))
        assertFalse(isEpisodeCompleted(-10L, 100_000L))
        assertFalse(isEpisodeCompleted(0L, 100_000L))
        assertFalse(isEpisodeCompleted(50_000L, -100L))
    }

    @Test
    fun completionThresholdHandlesShortEpisodes() {
        val duration = 20_000L
        assertFalse(isEpisodeCompleted(0L, duration))
        assertFalse(isEpisodeCompleted(5_000L, duration))
        assertFalse(isEpisodeCompleted(10_000L, duration))
        assertTrue(isEpisodeCompleted(19_000L, duration))
        assertTrue(isEpisodeCompleted(20_000L, duration))
    }

    @Test
    fun autoDeleteCleansUpFilesAndDatabaseForUnstarredEpisode() = runBlocking {
        val filesDir = kotlin.io.path.createTempDirectory("unsplice-test").toFile()
        try {
            val dao = TestDao()
            val epDir = File(filesDir, "episodes/ep-1").apply { mkdirs() }
            val audioFile = File(epDir, "audio.mp3").apply { writeText("audio content") }
            val fpFile = File(epDir, "fingerprints.bin").apply { writeText("fp content") }

            val episode = Episode(
                id = "ep-1",
                podcastId = "pod-1",
                title = "Episode One",
                audioUrl = "https://example.com/audio1.mp3",
                localPath = audioFile.absolutePath,
                fingerprintPath = fpFile.absolutePath,
                status = "READY",
                durationMs = 600_000L,
                positionMs = 580_000L,
                starred = false
            )
            dao.saveEpisode(episode)
            dao.saveSegments(listOf(
                AdSegment(id = 1L, episodeId = "ep-1", startMs = 10_000L, endMs = 30_000L)
            ))
            EpisodeProgressTracker.update("ep-1", EpisodeProgress("TEST", 50))

            val result = finishEpisodeAndCleanup(dao, filesDir, episode, autoDeleteEnabled = true)

            assertNotNull(result)
            assertEquals("NOT_DOWNLOADED", result.status)
            assertNull(result.localPath)
            assertNull(result.fingerprintPath)
            assertTrue(result.finished)
            assertEquals(0L, result.positionMs)

            val updatedInDb = dao.episode("ep-1")
            assertNotNull(updatedInDb)
            assertEquals("NOT_DOWNLOADED", updatedInDb!!.status)
            assertNull(updatedInDb.localPath)
            assertTrue(updatedInDb.finished)
            assertEquals(0L, updatedInDb.positionMs)

            assertFalse(epDir.exists())
            assertFalse(audioFile.exists())
            assertTrue(dao.segments("ep-1").isEmpty())
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun starredProtectionPreventsAutoDelete() = runBlocking {
        val filesDir = kotlin.io.path.createTempDirectory("unsplice-test").toFile()
        try {
            val dao = TestDao()
            val epDir = File(filesDir, "episodes/ep-starred").apply { mkdirs() }
            val audioFile = File(epDir, "audio.mp3").apply { writeText("starred audio") }
            val fpFile = File(epDir, "fingerprints.bin").apply { writeText("starred fp") }

            val episode = Episode(
                id = "ep-starred",
                podcastId = "pod-1",
                title = "Starred Episode",
                audioUrl = "https://example.com/starred.mp3",
                localPath = audioFile.absolutePath,
                fingerprintPath = fpFile.absolutePath,
                status = "READY",
                durationMs = 600_000L,
                positionMs = 590_000L,
                starred = true
            )
            dao.saveEpisode(episode)
            dao.saveSegments(listOf(
                AdSegment(id = 2L, episodeId = "ep-starred", startMs = 5_000L, endMs = 25_000L)
            ))

            val result = finishEpisodeAndCleanup(dao, filesDir, episode, autoDeleteEnabled = true)

            assertTrue(result.finished)
            assertEquals(0L, result.positionMs)
            assertTrue(result.starred)
            assertEquals(audioFile.absolutePath, result.localPath)
            assertEquals("READY", result.status)

            val inDb = dao.episode("ep-starred")
            assertNotNull(inDb)
            assertTrue(inDb!!.finished)
            assertEquals(0L, inDb.positionMs)
            assertEquals("READY", inDb.status)
            assertEquals(audioFile.absolutePath, inDb.localPath)

            assertTrue(audioFile.exists())
            assertTrue(fpFile.exists())
            assertEquals(1, dao.segments("ep-starred").size)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun autoDeleteDisabledPreservesFilesAndSegments() = runBlocking {
        val filesDir = kotlin.io.path.createTempDirectory("unsplice-test").toFile()
        try {
            val dao = TestDao()
            val epDir = File(filesDir, "episodes/ep-optout").apply { mkdirs() }
            val audioFile = File(epDir, "audio.mp3").apply { writeText("audio") }

            val episode = Episode(
                id = "ep-optout",
                podcastId = "pod-1",
                title = "Opt Out Episode",
                audioUrl = "https://example.com/optout.mp3",
                localPath = audioFile.absolutePath,
                status = "READY",
                durationMs = 300_000L,
                positionMs = 295_000L,
                starred = false
            )
            dao.saveEpisode(episode)
            dao.saveSegments(listOf(
                AdSegment(id = 3L, episodeId = "ep-optout", startMs = 1_000L, endMs = 20_000L)
            ))

            val result = finishEpisodeAndCleanup(dao, filesDir, episode, autoDeleteEnabled = false)

            assertTrue(result.finished)
            assertEquals(0L, result.positionMs)
            assertEquals(audioFile.absolutePath, result.localPath)
            assertEquals("READY", result.status)

            val inDb = dao.episode("ep-optout")
            assertNotNull(inDb)
            assertTrue(inDb!!.finished)
            assertEquals(0L, inDb.positionMs)
            assertEquals(audioFile.absolutePath, inDb.localPath)

            assertTrue(audioFile.exists())
            assertEquals(1, dao.segments("ep-optout").size)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun cleanupHandlesNotDownloadedEpisodeSafely() = runBlocking {
        val filesDir = kotlin.io.path.createTempDirectory("unsplice-test").toFile()
        try {
            val dao = TestDao()
            val episode = Episode(
                id = "ep-streamed",
                podcastId = "pod-1",
                title = "Streamed Only",
                audioUrl = "https://example.com/stream.mp3",
                localPath = null,
                status = "NOT_DOWNLOADED",
                durationMs = 500_000L,
                positionMs = 490_000L,
                starred = false
            )
            dao.saveEpisode(episode)

            val result = finishEpisodeAndCleanup(dao, filesDir, episode, autoDeleteEnabled = true)

            assertTrue(result.finished)
            assertEquals(0L, result.positionMs)
            assertEquals("NOT_DOWNLOADED", result.status)
            assertNull(result.localPath)

            val inDb = dao.episode("ep-streamed")
            assertNotNull(inDb)
            assertTrue(inDb!!.finished)
            assertEquals(0L, inDb.positionMs)
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test
    fun cleanupIsIdempotent() = runBlocking {
        val filesDir = kotlin.io.path.createTempDirectory("unsplice-test").toFile()
        try {
            val dao = TestDao()
            val epDir = File(filesDir, "episodes/ep-idem").apply { mkdirs() }
            val audioFile = File(epDir, "audio.mp3").apply { writeText("idem") }

            val episode = Episode(
                id = "ep-idem",
                podcastId = "pod-1",
                title = "Idempotent Episode",
                audioUrl = "https://example.com/idem.mp3",
                localPath = audioFile.absolutePath,
                status = "READY",
                durationMs = 100_000L,
                positionMs = 99_000L
            )
            dao.saveEpisode(episode)

            val first = finishEpisodeAndCleanup(dao, filesDir, episode, autoDeleteEnabled = true)
            assertTrue(first.finished)
            assertNull(first.localPath)

            val second = finishEpisodeAndCleanup(dao, filesDir, first, autoDeleteEnabled = true)
            assertTrue(second.finished)
            assertNull(second.localPath)
        } finally {
            filesDir.deleteRecursively()
        }
    }
}
