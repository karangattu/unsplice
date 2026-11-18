package com.echomute

import android.content.ComponentName
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.platform.app.InstrumentationRegistry
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

class QueueScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun episodesCanBeDraggedAndRemovedFromUpNext() {
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
                val feed = """<rss><channel><title>$show</title>
                    <item><title>$first</title><enclosure url="${server.url("/first.mp3")}" type="audio/mpeg"/></item>
                    <item><title>$second</title><pubDate>Fri, 25 Dec 2020 12:00:00 GMT</pubDate><enclosure url="${server.url("/second.mp3")}" type="audio/mpeg"/></item>
                    <item><title>$third</title><enclosure url="${server.url("/third.mp3")}" type="audio/mpeg"/></item>
                    </channel></rss>"""
                val mp3 = InstrumentationRegistry.getInstrumentation().context.assets.open("tone.mp3").use { it.readBytes() }
                // Queued episodes are downloaded in the background, so the player and the download both fetch audio.
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.path == "/feed") MockResponse().setBody(feed)
                        else MockResponse().setBody(Buffer().apply { repeat(100) { write(mp3) } })
                }
                compose.onNodeWithText("Discover").performClick()
                compose.onNodeWithText("Feed URL").assertDoesNotExist()
                compose.onNodeWithText("Add via RSS feed").performClick()
                compose.onNodeWithText("Feed URL").performTextInput(server.url("/feed").toString())
                compose.onNodeWithText("Subscribe").performClick()
                compose.onNodeWithText("Library").performClick()
                compose.waitUntil(15000) { compose.onAllNodes(hasText(show)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(show).performClick()
                compose.waitUntil(10000) { compose.onAllNodes(hasText(first)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("add-$first").performSemanticsAction(SemanticsActions.OnClick)
                compose.onNodeWithTag("episodes").performScrollToNode(hasTestTag("add-$second"))
                compose.onNodeWithTag("add-$second").performSemanticsAction(SemanticsActions.OnClick)
                compose.onNodeWithTag("episodes").performScrollToNode(hasTestTag("add-$third"))
                compose.onNodeWithTag("add-$third").performSemanticsAction(SemanticsActions.OnClick)
                compose.onNodeWithText("Up Next").performClick()
                compose.waitUntil(10000) {
                    compose.onAllNodes(hasTestTag("queue-$second")).fetchSemanticsNodes().isNotEmpty() &&
                        compose.onAllNodes(hasTestTag("queue-$third")).fetchSemanticsNodes().isNotEmpty()
                }
                fun row(title: String) = compose.onNodeWithTag("queue-$title").fetchSemanticsNode().boundsInRoot
                fun top(title: String) = row(title).top
                fun drag(from: String, toward: String) {
                    val distance = (row(toward).center.y - row(from).center.y) * 0.85f
                    compose.onNodeWithTag("reorder-$from").performTouchInput {
                        swipe(center, center + Offset(0f, distance), durationMillis = 400)
                    }
                }
                assertTrue(top(second) < top(third))
                compose.onNodeWithTag("reorder-$first").assertDoesNotExist()
                drag(third, second)
                compose.waitUntil(10000) { top(third) < top(second) }
                assertTrue(top(first) < top(third))
                drag(third, second)
                compose.waitUntil(10000) { top(second) < top(third) }
                val date = compose.onNodeWithText("25 Dec 2020", useUnmergedTree = true).assertIsDisplayed()
                val dateLayout = mutableListOf<TextLayoutResult>()
                date.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(dateLayout) }
                val layout = dateLayout.single()
                val lastLine = layout.lineCount - 1
                assertEquals("The full release date is visible", "25 Dec 2020".length, layout.getLineEnd(lastLine, visibleEnd = true))
                assertTrue(
                    date.fetchSemanticsNode().boundsInRoot.top >=
                        compose.onNodeWithText(second, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.bottom
                )
                compose.onNodeWithContentDescription("Queue actions for $third").performClick()
                compose.onNodeWithText("Move up").performClick()
                compose.waitUntil(10000) { top(third) < top(second) }
                compose.onNodeWithContentDescription("Queue actions for $second").performClick()
                compose.onNodeWithText("Remove from Up Next").performClick()
                compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("queue-$second")).fetchSemanticsNodes().isEmpty() }
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
