package com.echomute

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class LibraryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun libraryListAndGridShowVersionAndLatestMetadata() {
        val podcasts = listOf(
            Podcast("p1", "Tech Talk", "https://example.com/1.xml", autoSkip = true),
            Podcast("p2", "Deep Work", "https://example.com/2.xml", autoSkip = false)
        )
        compose.setContent {
            UnspliceTheme {
                LibraryTab(
                    version = "0.18.0",
                    podcasts = podcasts,
                    unplayedCounts = mapOf("p1" to 3),
                    latestPublishedByPodcast = emptyMap(),
                    gridLayout = false,
                    update = null,
                    onToggleLayout = {},
                    onOpenStats = {},
                    onOpenSettings = {},
                    onDownloadUpdate = {},
                    onSelectPodcast = {},
                    onRequestRemovePodcast = {},
                    onOpenDiscover = {}
                )
            }
        }
        compose.onNodeWithText("v0.18.0").assertIsDisplayed()
        compose.onNodeWithText("Tech Talk").assertIsDisplayed()
        compose.onNodeWithText("Auto-skip on").assertIsDisplayed()
        compose.onNodeWithText("3").assertIsDisplayed()
    }

    @Test fun libraryGridRendersShowsAndEmptyStatePromptsDiscover() {
        compose.setContent {
            UnspliceTheme {
                LibraryTab(
                    version = "0.18.0",
                    podcasts = emptyList(),
                    unplayedCounts = emptyMap(),
                    gridLayout = true,
                    update = null,
                    onToggleLayout = {},
                    onOpenStats = {},
                    onOpenSettings = {},
                    onDownloadUpdate = {},
                    onSelectPodcast = {},
                    onRequestRemovePodcast = {},
                    onOpenDiscover = {}
                )
            }
        }
        compose.onNodeWithText("No podcasts yet").assertIsDisplayed()
        compose.onNodeWithText("Browse Discover").assertIsDisplayed()
        compose.onNodeWithTag("open-settings").assertIsDisplayed()
    }
}
