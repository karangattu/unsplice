package com.echomute

import android.content.ComponentName
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.TimeUnit

class PlayerAdBreaksTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun adBreaksOpenWithoutScrollingThePlayer() {
        val activity = compose.activity
        val token = SessionToken(activity, ComponentName(activity, PlaybackService::class.java))
        val future = MediaController.Builder(activity, token).buildAsync()
        val controller = future.get(10, TimeUnit.SECONDS)
        try {
            compose.runOnIdle {
                controller.stop()
                controller.setMediaItem(QueueItem("ad-breaks-test", "https://example.com/audio.mp3", "Episode", "Show").toMediaItem())
            }
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasContentDescription("Expand player")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Expand player").performClick()
            compose.onNodeWithText("Ad breaks").assertIsDisplayed().performClick()
            val empty = compose.onNodeWithText("No ad breaks detected yet for this episode.")
            empty.assertIsDisplayed()
            compose.onNodeWithContentDescription("Close ad breaks").performClick()
            empty.assertDoesNotExist()
        } finally {
            compose.runOnIdle {
                controller.clearMediaItems()
                MediaController.releaseFuture(future)
            }
        }
    }
}
