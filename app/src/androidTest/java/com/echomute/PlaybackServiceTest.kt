package com.echomute

import android.content.ComponentName
import androidx.media3.common.MediaItem
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PlaybackServiceTest {
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
