package com.echomute

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

class PodcastArtworkTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsArtworkFromUrl() {
        MockWebServer().use { server ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val image = context.resources.openRawResource(R.drawable.ic_launcher).use { it.readBytes() }
            server.enqueue(MockResponse().setBody(Buffer().write(image)))
            val url = server.url("/cover.png").toString()
            compose.setContent { PodcastArtwork("Example Show", url) }
            compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("podcast-artwork-image")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("podcast-artwork-image").assertExists()
        }
    }

    @Test fun showsInitialsWhenArtworkIsUnavailable() {
        compose.setContent { PodcastArtwork("Example Show", null) }
        compose.onNodeWithText("EX").assertExists()
        compose.onNodeWithTag("podcast-artwork-image").assertDoesNotExist()
    }

    @Test fun keepsInitialsWhenArtworkRequestFails() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404))
            val url = server.url("/missing.png").toString()
            compose.setContent { PodcastArtwork("Example Show", url) }
            checkNotNull(server.takeRequest(10, TimeUnit.SECONDS))
            compose.waitForIdle()
            compose.onNodeWithText("EX").assertExists()
            compose.onNodeWithTag("podcast-artwork-image").assertDoesNotExist()
        }
    }

    @Test fun keepsInitialsForMalformedArtworkUrl() {
        compose.setContent { PodcastArtwork("Example Show", "http://example.com:999999/art") }
        compose.waitForIdle()
        compose.onNodeWithText("EX").assertExists()
    }
}
