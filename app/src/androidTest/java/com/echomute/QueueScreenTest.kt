package com.echomute

import android.content.ComponentName
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

class QueueScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun episodesCanBePlayedReorderedAndRemovedFromUpNext() {
        val activity = compose.activity
        val controllerFuture = MediaController.Builder(activity, SessionToken(activity, ComponentName(activity, PlaybackService::class.java))).buildAsync()
        val controller = controllerFuture.get(10, TimeUnit.SECONDS)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            controller.stop()
            controller.clearMediaItems()
        }
        try {
            MockWebServer().use { server ->
                val show = "Queue Test ${System.nanoTime()}"
                val first = "First ${System.nanoTime()}"
                val second = "Second ${System.nanoTime()}"
                val third = "Third ${System.nanoTime()}"
                val feed = """<rss><channel><title>$show</title><item><title>$first</title><enclosure url="${server.url("/first.mp3")}" type="audio/mpeg"/></item><item><title>$second</title><enclosure url="${server.url("/second.mp3")}" type="audio/mpeg"/></item><item><title>$third</title><enclosure url="${server.url("/third.mp3")}" type="audio/mpeg"/></item></channel></rss>"""
                val mp3 = InstrumentationRegistry.getInstrumentation().context.assets.open("tone.mp3").use { it.readBytes() }
                // Queued episodes are downloaded in the background, so the player and the download both fetch audio.
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.path == "/feed") MockResponse().setBody(feed)
                        else MockResponse().setBody(Buffer().apply { repeat(100) { write(mp3) } })
                }
                compose.onNodeWithText("Discover").performClick()
                compose.onNodeWithText("Feed URL").performTextInput(server.url("/feed").toString())
                compose.onNodeWithText("Subscribe").performClick()
                compose.onNodeWithText("Library").performClick()
                compose.waitUntil(15000) { compose.onAllNodes(hasText(show)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(show).performClick()
                compose.waitUntil(10000) { compose.onAllNodes(hasText(first)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("add-$first").performClick()
                compose.waitUntil(10000) { compose.onAllNodes(hasContentDescription("Resume playback")).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Resume playback").performClick()
                compose.waitUntil(10000) { compose.onAllNodes(hasContentDescription("Pause playback")).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("Pause playback").performClick()
                compose.onNodeWithTag("episodes").performScrollToNode(hasText(second))
                compose.onNodeWithTag("add-$second").performClick()
                compose.onNodeWithTag("episodes").performScrollToNode(hasText(third))
                compose.onNodeWithTag("add-$third").performClick()
                compose.onNodeWithText("Up Next").performClick()
                fun top(title: String) = compose.onNodeWithTag("queue-$title").fetchSemanticsNode().boundsInRoot.top
                assertTrue(top(second) < top(third))
                compose.onNodeWithContentDescription("Move $third up").performClick()
                compose.waitUntil(10000) { top(third) < top(second) }
                assertTrue(top(third) < top(second))
                compose.onNodeWithContentDescription("Remove $second from Up Next").performClick()
                compose.onNodeWithTag("queue-$second").assertDoesNotExist()
                compose.onNodeWithTag("queue-$third").assertExists()
            }
        } finally {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                controller.stop()
                controller.clearMediaItems()
                MediaController.releaseFuture(controllerFuture)
            }
        }
    }
}
