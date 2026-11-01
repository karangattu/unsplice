package com.echomute

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerFeaturesTest {
    @Test fun extractsClickableTimestampsFromShowNotes() {
        val notes = "00:30 Cold open\n14:20 Deep dive\n1:02:15 Listener Q&A"
        val found = extractTimestamps(notes)
        assertEquals(
            listOf(
                TimestampLink("00:30", 30_000L),
                TimestampLink("14:20", 860_000L),
                TimestampLink("1:02:15", 3_735_000L)
            ),
            found
        )
    }

    @Test fun groupsAndFiltersTranscriptLines() {
        val words = listOf(
            TranscriptWord(0L, 1_500L, "Welcome"),
            TranscriptWord(1_500L, 3_000L, "back"),
            TranscriptWord(10_000L, 12_000L, "Brought"),
            TranscriptWord(12_000L, 14_000L, "to you by Acme")
        )
        val lines = groupTranscriptLines(words, windowMs = 8_000L)
        assertEquals(2, lines.size)
        assertEquals(TranscriptLine(0L, 3_000L, "Welcome back"), lines[0])
        assertEquals(TranscriptLine(10_000L, 14_000L, "Brought to you by Acme"), lines[1])
        assertEquals(listOf(lines[1]), filterTranscriptLines(lines, "acme"))
    }

    @Test fun filtersCrossPodcastSmartViewsAndCountsUnplayed() {
        val episodes = listOf(
            Episode("1", "p1", "Fresh", "https://a/1.mp3", publishedAt = 3_000L),
            Episode("2", "p1", "Halfway", "https://a/2.mp3", positionMs = 45_000L, durationMs = 90_000L, publishedAt = 2_000L),
            Episode("3", "p2", "Offline", "https://a/3.mp3", localPath = "/tmp/3.mp3", status = "READY", starred = true, publishedAt = 1_000L),
            Episode("4", "p2", "Done", "https://a/4.mp3", finished = true, publishedAt = 4_000L)
        )
        assertEquals(listOf("1", "2", "3"), filterSmartEpisodes(episodes, "New Releases").map { it.id })
        assertEquals(listOf("2"), filterSmartEpisodes(episodes, "In Progress").map { it.id })
        assertEquals(listOf("3"), filterSmartEpisodes(episodes, "Downloaded").map { it.id })
        assertEquals(listOf("3"), filterSmartEpisodes(episodes, "Starred").map { it.id })
        assertEquals(mapOf("p1" to 2, "p2" to 1), unplayedCountsByPodcast(episodes))
    }

    @Test fun recordsAndReadsListeningStatsAndTimeSaved() {
        val prefs = FakePrefs()
        ListeningStatsStore.recordAdSkipped(prefs, 30_000L)
        ListeningStatsStore.recordAdSkipped(prefs, 15_000L)
        ListeningStatsStore.recordPlaybackTick(prefs, wallElapsedMs = 2_000L, speed = 1.5f, trimSilence = true)
        val stats = ListeningStatsStore.read(prefs)
        assertEquals(45_000L, stats.adSkippedMs)
        assertEquals(3_000L, stats.totalListenedMs)
        assertEquals(1_000L, stats.speedSavedMs)
        assertTrue(stats.silenceSavedMs > 0L)
        assertEquals(stats.adSkippedMs + stats.speedSavedMs + stats.silenceSavedMs, stats.totalSavedMs)
    }

    @Test fun stepsPlaybackSpeedWithinBounds() {
        assertEquals(1.1f, stepSpeed(1.0f, 0.1f), 0.001f)
        assertEquals(0.5f, stepSpeed(0.5f, -0.1f), 0.001f)
        assertEquals(3.0f, stepSpeed(3.0f, 0.1f), 0.001f)
    }

    @Test fun calculatesPerPodcastStorageUsage() {
        val root = File.createTempFile("storage", "").apply { delete(); mkdirs() }
        try {
            val epDir = File(root, "episodes/e1").apply { mkdirs() }
            File(epDir, "audio").writeBytes(ByteArray(2048))
            val podcasts = listOf(Podcast("p1", "Show One", "https://a"), Podcast("p2", "Show Two", "https://b"))
            val episodes = listOf(Episode("e1", "p1", "Ep 1", "https://a/1", localPath = File(epDir, "audio").absolutePath, status = "READY"))
            val usage = calculatePodcastStorage(root, podcasts, episodes)
            assertEquals(1, usage.size)
            assertEquals("p1", usage.single().podcastId)
            assertEquals(2048L, usage.single().bytes)
        } finally {
            root.deleteRecursively()
        }
    }
}
