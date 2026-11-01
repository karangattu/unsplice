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
            "Transcribing audio for sponsor reads \u2022 60% \u2022 ~1m 25s left",
            EpisodeProgress("Transcribing audio for sponsor reads", percent = 60, remainingMs = 85_000).summary()
        )
        assertEquals(
            "Transcribing audio for sponsor reads \u2022 10% \u2022 ~12m left",
            EpisodeProgress("Transcribing audio for sponsor reads", percent = 10, remainingMs = 12 * 60_000L).summary()
        )
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
}
