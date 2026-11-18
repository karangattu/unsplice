package com.echomute

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import java.io.File
import org.junit.Rule
import org.junit.Test

class ShowDetailScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showDetailRendersHeaderAutoSkipAndEpisodeSegments() {
        val podcast = Podcast("p1", "Example Show", "https://example.com/feed", autoSkip = true)
        val episode = Episode(
            id = "e1",
            podcastId = "p1",
            title = "Episode One",
            audioUrl = "https://example.com/1.mp3",
            status = "READY",
            positionMs = 30_000L,
            durationMs = 120_000L
        )
        val segments = listOf(AdSegment(1L, "e1", 10_000L, 40_000L, "marker", 0.9f))
        val filesDir = kotlin.io.path.createTempDirectory("show-detail-test").toFile()
        try {
            compose.setContent {
                UnspliceTheme {
                    ShowDetailScreen(
                        podcast = podcast,
                        episodes = listOf(episode),
                        visibleEpisodes = listOf(episode),
                        unplayedCount = 1,
                        segmentsByEpisode = mapOf("e1" to segments),
                        activeProgressMap = emptyMap(),
                        filesDir = filesDir,
                        refreshing = false,
                        podcastSettingsOpen = false,
                        episodeFilter = "All",
                        episodeQuery = "",
                        episodeSearchOpen = false,
                        oldestFirst = false,
                        expandedSegmentSet = setOf("e1"),
                        expandedNotesSet = emptySet(),
                        onBack = {},
                        onRefresh = {},
                        onTogglePodcastSettings = {},
                        onUpdatePodcast = {},
                        onRequestRemovePodcast = {},
                        onFilterChange = {},
                        onToggleSearch = {},
                        onQueryChange = {},
                        onToggleSort = {},
                        onToggleNotes = {},
                        onToggleSegments = {},
                        onToggleStar = {},
                        onSeekTimestamp = { _, _ -> },
                        onKeepSegment = {},
                        onScanAgain = {},
                        onOpenTranscript = {},
                        onPlayEpisode = {},
                        onPlayNext = {},
                        onDownloadEpisode = {},
                        onCancelEpisode = {},
                        onAddToQueue = {},
                        onDeleteDownload = {},
                        onToggleFinished = {}
                    )
                }
            }
            compose.onNodeWithText("Example Show").assertIsDisplayed()
            compose.onNodeWithText("Automatically skip detected ads").assertIsDisplayed()
            compose.onNodeWithText("Episode One").assertIsDisplayed()
            compose.onNodeWithTag("toggle-segments-e1").assertIsDisplayed()
            compose.onNodeWithText("Keep audio").assertIsDisplayed()
        } finally {
            filesDir.deleteRecursively()
        }
    }

    @Test fun listeningStatsSheetDisplaysSavedTimeBreakdown() {
        compose.setContent {
            UnspliceTheme {
                ListeningStatsDialog(
                    stats = ListeningStats(
                        totalListenedMs = 3_600_000L,
                        adSkippedMs = 180_000L,
                        speedSavedMs = 300_000L,
                        silenceSavedMs = 60_000L
                    ),
                    onDismiss = {}
                )
            }
        }
        compose.onNodeWithText("Listening Stats").assertIsDisplayed()
        compose.onNodeWithText("TOTAL TIME SAVED").assertIsDisplayed()
        compose.onNodeWithText("Ads & intros skipped").assertIsDisplayed()
    }
}
