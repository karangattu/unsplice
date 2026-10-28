package com.echomute

import android.content.ComponentName
import android.content.Intent
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlaybackServiceTest {
    @Test fun mediaSessionOpensTheApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        val controller = future.get(15, TimeUnit.SECONDS)
        try {
            var activityIntent: android.app.PendingIntent? = null
            instrumentation.runOnMainSync { activityIntent = controller.sessionActivity }
            assertNotNull("The media notification must have an activity to open", activityIntent)
            activityIntent!!.send()
            val opened = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
            assertTrue("The media notification should open the app", opened is MainActivity)
            opened?.finish()
        } finally {
            instrumentation.removeMonitor(monitor)
            instrumentation.runOnMainSync { controller.release() }
        }
    }

    @Test fun playsDownloadedAudioThroughMediaSession() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "playback-test.mp3")
        instrumentation.context.assets.open("tone.mp3").use { input -> file.outputStream().use { input.copyTo(it) } }
        val dao = LibraryDatabase.get(context).library()
        runBlocking {
            dao.savePodcast(Podcast("playback-test", "Test", "https://example.com"))
            dao.saveEpisode(Episode("playback-episode", "playback-test", "Tone", "https://example.com/tone", localPath = file.path, status = "READY"))
        }
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        val controller = future.get(15, TimeUnit.SECONDS)
        try {
            instrumentation.runOnMainSync {
                controller.setMediaItem(MediaItem.Builder().setMediaId("playback-episode").setUri(file.toURI().toString()).build())
                controller.prepare()
                controller.play()
            }
            var position = 0L
            repeat(30) {
                Thread.sleep(100)
                instrumentation.runOnMainSync { position = controller.currentPosition }
                if (position > 200) return@repeat
            }
            assertTrue("Playback position should advance", position > 200)
        } finally {
            instrumentation.runOnMainSync { controller.pause(); controller.release() }
            file.delete()
        }
    }

    @Test fun queueAdvancesAndRestoresItsOrderAndPosition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "queue-test.mp3")
        instrumentation.context.assets.open("tone.mp3").use { input -> file.outputStream().use { input.copyTo(it) } }
        val prefs = context.getSharedPreferences("playback_queue", android.content.Context.MODE_PRIVATE)
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        fun connect() = MediaController.Builder(context, token).buildAsync().get(15, TimeUnit.SECONDS)
        val controller = connect()
        try {
            instrumentation.runOnMainSync {
                val one = MediaItem.Builder().setMediaId("queue-one").setUri(file.toURI().toString()).build()
                val two = MediaItem.Builder().setMediaId("queue-two").setUri(file.toURI().toString()).build()
                controller.setMediaItems(listOf(one, two))
                controller.moveMediaItem(1, 0)
                controller.prepare()
                controller.seekTo(0, 1_800)
                controller.play()
            }
            var advanced = false
            for (attempt in 0 until 60) {
                Thread.sleep(100)
                instrumentation.runOnMainSync { advanced = controller.currentMediaItem?.mediaId == "queue-one" }
                if (advanced) break
            }
            assertTrue("Playback should advance to the next queued episode", advanced)
            instrumentation.runOnMainSync { controller.pause(); controller.seekTo(700) }
            var saved: PlaybackQueueSnapshot? = null
            for (attempt in 0 until 30) {
                Thread.sleep(100)
                saved = prefs.getString("snapshot", null)?.let(::decodePlaybackQueue)
                if (saved?.index == 1 && saved?.position == 700L) break
            }
            assertEquals(listOf("queue-two", "queue-one"), saved?.items?.map { it.id })
            assertEquals(1, saved?.index)
            assertEquals(700L, saved?.position)
        } finally {
            instrumentation.runOnMainSync { controller.release() }
            context.stopService(Intent(context, PlaybackService::class.java))
        }
        // Wait for the unbound service to finish saving before opening a fresh session.
        Thread.sleep(1_000)
        val restored = connect()
        try {
            instrumentation.runOnMainSync {
                assertEquals(2, restored.mediaItemCount)
                assertEquals("queue-two", restored.getMediaItemAt(0).mediaId)
                assertEquals("queue-one", restored.currentMediaItem?.mediaId)
                assertEquals(700L, restored.currentPosition)
                assertTrue("Restored playback must stay paused", !restored.playWhenReady)
                restored.clearMediaItems()
            }
        } finally {
            instrumentation.runOnMainSync { restored.release() }
            context.stopService(Intent(context, PlaybackService::class.java))
            file.delete()
        }
    }

    @Test fun skipsAutoSkipSegmentAtItsStart() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "skip-test.mp3")
        instrumentation.context.assets.open("tone.mp3").use { input -> file.outputStream().use { input.copyTo(it) } }
        val dao = LibraryDatabase.get(context).library()
        val prefs = context.getSharedPreferences("playback", android.content.Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        runBlocking {
            dao.savePodcast(Podcast("skip-test", "Test", "https://example.com", autoSkip = true))
            dao.saveEpisode(Episode("skip-episode", "skip-test", "Tone", "https://example.com/tone", localPath = file.path, status = "READY"))
            dao.deleteSegments("skip-episode")
            dao.saveSegments(listOf(AdSegment(episodeId = "skip-episode", startMs = 300, endMs = 1_700, source = "chapter", confidence = 0.9f)))
        }
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        val controller = future.get(15, TimeUnit.SECONDS)
        try {
            instrumentation.runOnMainSync {
                controller.setMediaItem(MediaItem.Builder().setMediaId("skip-episode").setUri(file.toURI().toString()).build())
                controller.prepare()
                controller.play()
            }
            // Without the skip, reaching 1.7 s of a 2 s tone would take about 1.7 s of wall time.
            var reached = false
            repeat(20) {
                Thread.sleep(50)
                var position = 0L
                instrumentation.runOnMainSync { position = controller.currentPosition }
                if (position >= 1_700) { reached = true; return@repeat }
            }
            assertTrue("Playback should jump past the ad", reached)
            assertEquals(300L, prefs.getLong("skipStart", -1))
        } finally {
            instrumentation.runOnMainSync { controller.pause(); controller.release() }
            file.delete()
            runBlocking { dao.deleteEpisode("skip-episode"); dao.deletePodcast("skip-test") }
        }
    }
}
