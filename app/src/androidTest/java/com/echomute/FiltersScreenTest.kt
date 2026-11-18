package com.echomute

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class FiltersScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun episodeDateHasItsOwnLineBelowLongShowTitle() {
        val show = Podcast("show", "A Long Podcast Title With Multiple Hosts", "https://example.com/feed")
        val episode = Episode(
            id = "episode",
            podcastId = show.id,
            title = "An Episode About Designing for People",
            audioUrl = "https://example.com/audio.mp3",
            publishedAt = LocalDate.of(2020, 12, 25).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        )
        compose.setContent {
            UnspliceTheme {
                FiltersTab("New Releases", "", listOf(episode), mapOf(show.id to show), {}, {}, {}, {}, { _, _ -> })
            }
        }
        compose.onNodeWithText("25 Dec 2020").assertIsDisplayed()
    }
}
