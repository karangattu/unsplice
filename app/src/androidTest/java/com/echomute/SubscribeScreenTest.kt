package com.echomute

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import androidx.test.platform.app.InstrumentationRegistry
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

class SubscribeScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun subscribesToAFeedAndShowsEpisodes() {
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(compose.activity.packageName, Manifest.permission.POST_NOTIFICATIONS)
        MockWebServer().use { server ->
            val show = "Device Test Show ${System.nanoTime()}"
            val audioUrl = server.url("/episode.mp3")
            server.enqueue(MockResponse().setBody("""<rss><channel><title>$show</title><item><title>Device Test Episode</title><enclosure url="$audioUrl" type="audio/mpeg"/></item></channel></rss>"""))
            val mp3 = InstrumentationRegistry.getInstrumentation().context.assets.open("tone.mp3").use { it.readBytes() }
            server.enqueue(MockResponse().setBody(Buffer().write(mp3)))
            compose.onNodeWithText("Discover").performClick()
            compose.onNodeWithText("Feed URL").performTextInput(server.url("/feed").toString())
            compose.onNodeWithText("Subscribe").performClick()
            compose.onNodeWithText("Library").performClick()
            compose.waitUntil(15000) { compose.onAllNodes(androidx.compose.ui.test.hasText(show)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(show).performClick()
            compose.waitUntil(10000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Device Test Episode")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Device Test Episode").assertExists()
            compose.onNodeWithText("Download").performClick()
            compose.waitUntil(60000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Delete")).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Play").performClick()
            compose.waitUntil(10000) { compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Pause playback")).fetchSemanticsNodes().isNotEmpty() }
        }
    }

    @Test fun downloadContinuesWhenNotificationPermissionIsDenied() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        assumeTrue(InstrumentationRegistry.getArguments().getString("runPermissionDenialTest") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        assumeTrue(ContextCompat.checkSelfPermission(compose.activity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
        try {
            MockWebServer().use { server ->
                val show = "Permission Test ${System.nanoTime()}"
                val episode = "Permission episode ${System.nanoTime()}"
                val url = server.url("/episode.mp3")
                server.enqueue(MockResponse().setBody("""<rss><channel><title>$show</title><item><title>$episode</title><enclosure url="$url" type="audio/mpeg"/></item></channel></rss>"""))
                val mp3 = instrumentation.context.assets.open("tone.mp3").use { it.readBytes() }
                server.enqueue(MockResponse().setBody(Buffer().write(mp3)))
                compose.onNodeWithText("Discover").performClick()
                compose.onNodeWithText("Feed URL").performTextInput(server.url("/feed").toString())
                compose.onNodeWithText("Subscribe").performClick()
                compose.onNodeWithText("Library").performClick()
                compose.waitUntil(15000) { compose.onAllNodes(androidx.compose.ui.test.hasText(show)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(show).performClick()
                compose.waitUntil(10000) { compose.onAllNodes(androidx.compose.ui.test.hasText(episode)).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText("Download").performClick()
                var deny: AccessibilityNodeInfo? = null
                compose.waitUntil(10000) {
                    deny = automation.rootInActiveWindow?.findAccessibilityNodeInfosByViewId("com.android.permissioncontroller:id/permission_deny_button")?.firstOrNull()
                    deny != null
                }
                check(deny!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                val dao = LibraryDatabase.get(compose.activity).library()
                compose.waitUntil(60000) {
                    runBlocking {
                        val podcast = dao.observePodcasts().first().firstOrNull { it.title == show }
                        podcast != null && dao.observeEpisodes(podcast.id).first().any { it.title == episode && it.status == "READY" }
                    }
                }
            }
        } finally {
            automation.grantRuntimePermission(compose.activity.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
