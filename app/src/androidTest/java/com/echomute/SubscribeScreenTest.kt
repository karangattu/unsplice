package com.echomute

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class SubscribeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun subscribesToAFeedAndShowsEpisodes() {
        MockWebServer().use { server ->
            val show = "Device Test Show ${System.nanoTime()}"
            val audioUrl = server.url("/episode.mp3")
            server.enqueue(MockResponse().setBody("""<rss><channel><title>$show</title><item><title>Device Test Episode</title><enclosure url="$audioUrl" type="audio/mpeg"/></item></channel></rss>"""))
            val mp3 = InstrumentationRegistry.getInstrumentation().context.assets.open("tone.mp3").use { it.readBytes() }
            server.enqueue(MockResponse().setBody(Buffer().write(mp3)))
            compose.onNodeWithText("Feed URL").performTextInput(server.url("/feed").toString())
            compose.onNodeWithText("Subscribe").performClick()
            compose.waitUntil(15000) { compose.onAllNodes(androidx.compose.ui.test.hasText(show)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(show).performClick()
            compose.waitUntil(10000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Device Test Episode")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Device Test Episode").assertExists()
            compose.onNodeWithText("Download").performClick()
            compose.waitUntil(60000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Play")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Play").performClick()
            compose.waitUntil(10000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Pause")).fetchSemanticsNodes().isNotEmpty() }
        }
    }
}
