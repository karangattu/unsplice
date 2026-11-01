package com.echomute

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class EpisodeProgressTest {
    @Test fun formatsIndeterminateAndDeterminateSummariesWithEta() {
        assertEquals("Downloading episode", EpisodeProgress("Downloading episode").summary())
        assertEquals(
            "Downloading episode \u2022 42% (10.5 / 25.0 MB) \u2022 ~14s left",
            EpisodeProgress("Downloading episode", percent = 42, remainingMs = 13_400, detail = "10.5 / 25.0 MB").summary()
        )
        assertEquals(
            "Scanning for repeated ad audio \u2022 60% \u2022 ~1m 25s left",
            EpisodeProgress("Scanning for repeated ad audio", percent = 60, remainingMs = 85_000).summary()
        )
        assertEquals(
            "Scanning for repeated ad audio \u2022 10% \u2022 ~12m left",
            EpisodeProgress("Scanning for repeated ad audio", percent = 10, remainingMs = 12 * 60_000L).summary()
        )
    }

    @Test fun expectedProcessingStagesExcludeAudioTranscription() {
        val stages = listOf(
            "Downloading episode",
            "Decoding audio",
            "Checking ad markers and chapters",
            "Scanning for repeated ad audio"
        )
        assertTrue(stages.none { it.contains("transcrib", ignoreCase = true) })
        assertTrue(stages.none { it.contains("sponsor read", ignoreCase = true) })
    }

    @Test fun estimatesRemainingTimeAndByteProgress() {
        assertNull(estimateRemainingMs(done = 0, total = 100, elapsedMs = 2_000))
        assertNull(estimateRemainingMs(done = 50, total = 100, elapsedMs = 200))
        assertNull(estimateRemainingMs(done = 100, total = 100, elapsedMs = 5_000))
        assertEquals(6_000L, estimateRemainingMs(done = 25, total = 100, elapsedMs = 2_000))

        val step = buildStepProgress("Downloading episode", done = 5_000_000, total = 10_000_000, elapsedMs = 4_000, includeBytes = true)
        assertEquals(50, step.percent)
        assertEquals(4_000L, step.remainingMs)
        assertEquals("5.0 / 10.0 MB", step.detail)
        assertEquals("Downloading episode \u2022 50% (5.0 / 10.0 MB) \u2022 ~4s left", step.summary())
    }

    @Test fun trackerStoresUpdatesAndClearsByEpisodeId() = runBlocking {
        val id = "ep-progress-test"
        EpisodeProgressTracker.clear(id)
        assertNull(EpisodeProgressTracker.observe(id).first())
        val progress = EpisodeProgress("Decoding audio", percent = 35, remainingMs = 3_000)
        EpisodeProgressTracker.update(id, progress)
        assertEquals(progress, EpisodeProgressTracker.observe(id).first())
        EpisodeProgressTracker.clear(id)
        assertNull(EpisodeProgressTracker.observe(id).first())
    }

    @Test fun stepReporterEmitsFirstDeterminateUpdateImmediatelyAndThrottlesRapidTicks() {
        var now = 1_000L
        val emitted = mutableListOf<EpisodeProgress>()
        val id = "ep-reporter-test"
        try {
            val reporter = StepProgressReporter(id, onProgress = { emitted.add(it) }, clock = { now })
            reporter.beginStage("Downloading episode")
            assertEquals(listOf(EpisodeProgress("Downloading episode")), emitted)

            now = 1_100L
            reporter.report(done = 2_500_000, total = 10_000_000, includeBytes = true)
            assertEquals(2, emitted.size)
            assertEquals(25, emitted.last().percent)
            assertNull(emitted.last().remainingMs)

            now = 1_200L
            reporter.report(done = 3_000_000, total = 10_000_000, includeBytes = true)
            assertEquals(2, emitted.size)

            now = 2_000L
            reporter.report(done = 5_000_000, total = 10_000_000, includeBytes = true)
            assertEquals(3, emitted.size)
            assertEquals(50, emitted.last().percent)
            assertEquals(1_000L, emitted.last().remainingMs)
        } finally {
            EpisodeProgressTracker.clear(id)
        }
    }

    @Test fun formatsAdSummaryWithTotalTimeSaved() {
        assertNull(formatAdSummary(emptyList(), autoSkip = true))
        val segments = listOf(
            AdSegment(id = 1, episodeId = "e1", startMs = 10_000, endMs = 70_000, source = "marker", confidence = 0.9f),
            AdSegment(id = 2, episodeId = "e1", startMs = 120_000, endMs = 150_000, source = "transcript", confidence = 0.45f)
        )
        assertEquals("2 ad segment(s) detected \u2022 1m skipped", formatAdSummary(segments, autoSkip = true))
        assertEquals("2 ad segment(s) detected \u2022 1m 30s total", formatAdSummary(segments, autoSkip = false))
    }

    @Test fun filtersAndSortsEpisodesByQueryAndState() {
        val ep1 = Episode(id = "1", podcastId = "p", title = "Intro to Kotlin", audioUrl = "u1", finished = true)
        val ep2 = Episode(id = "2", podcastId = "p", title = "Deep Dive Coroutines", audioUrl = "u2", localPath = "/tmp/a.mp3", status = "READY", finished = false)
        val ep3 = Episode(id = "3", podcastId = "p", title = "Kotlin Flow", audioUrl = "u3", finished = false)
        val all = listOf(ep3, ep2, ep1)

        assertEquals(listOf("3", "2", "1"), filterEpisodes(all, query = "", filter = "All", oldestFirst = false).map { it.id })
        assertEquals(listOf("1", "2", "3"), filterEpisodes(all, query = "", filter = "All", oldestFirst = true).map { it.id })
        assertEquals(listOf("3", "2"), filterEpisodes(all, query = "", filter = "Unplayed", oldestFirst = false).map { it.id })
        assertEquals(listOf("2"), filterEpisodes(all, query = "", filter = "Downloaded", oldestFirst = false).map { it.id })
        assertEquals(listOf("3", "1"), filterEpisodes(all, query = "kotlin", filter = "All", oldestFirst = false).map { it.id })
    }

    @Test fun detectsWhetherFeedIsAlreadySubscribed() {
        val url = "https://feeds.example.com/show.xml"
        val podcasts = listOf(Podcast(id = stableHash(url), title = "Show", feedUrl = url))
        assertTrue(isPodcastSubscribed(podcasts, url))
        assertFalse(isPodcastSubscribed(podcasts, "https://feeds.example.com/other.xml"))
    }
}
