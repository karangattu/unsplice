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

/** Writes a mono 8 kHz 16-bit 440 Hz tone. */
private fun writeToneWav(file: File, seconds: Int) {
    val rate = 8000
    val samples = rate * seconds
    val data = java.nio.ByteBuffer.allocate(44 + samples * 2).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    data.put("RIFF".toByteArray()).putInt(36 + samples * 2).put("WAVE".toByteArray())
    data.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
    data.put("data".toByteArray()).putInt(samples * 2)
    for (i in 0 until samples) data.putShort((Math.sin(2 * Math.PI * 440 * i / rate) * 8000).toInt().toShort())
    file.writeBytes(data.array())
}

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

    @Test fun skipsPreRollWhenTheQueueAdvancesToTheNextEpisode() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val file = File(context.cacheDir, "advance-skip-test.mp3")
        instrumentation.context.assets.open("tone.mp3").use { input -> file.outputStream().use { input.copyTo(it) } }
        val dao = LibraryDatabase.get(context).library()
        runBlocking {
            dao.savePodcast(Podcast("advance-test", "Test", "https://example.com", autoSkip = true))
            dao.saveEpisode(Episode("advance-first", "advance-test", "First", "https://example.com/1", localPath = file.path, status = "READY"))
            dao.saveEpisode(Episode("advance-second", "advance-test", "Second", "https://example.com/2", localPath = file.path, status = "READY"))
            dao.deleteSegments("advance-second")
            // A pre-roll ad fills the first 1.5 s of a 2 s tone.
            dao.saveSegments(listOf(AdSegment(episodeId = "advance-second", startMs = 0, endMs = 1_500, source = "marker", confidence = 0.9f)))
        }
        val controller = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync().get(15, TimeUnit.SECONDS)
        try {
            instrumentation.runOnMainSync {
                controller.setMediaItems(listOf(
                    MediaItem.Builder().setMediaId("advance-first").setUri(file.toURI().toString()).build(),
                    MediaItem.Builder().setMediaId("advance-second").setUri(file.toURI().toString()).build(),
                ))
                controller.prepare()
                controller.seekTo(0, 1_800)
                controller.play()
            }
            var advancedAt = 0L
            for (attempt in 0 until 60) {
                Thread.sleep(50)
                var onSecond = false
                instrumentation.runOnMainSync { onSecond = controller.currentMediaItem?.mediaId == "advance-second" }
                if (onSecond) { advancedAt = System.currentTimeMillis(); break }
            }
            assertTrue("Playback should advance to the second episode", advancedAt > 0)
            // Without the skip, reaching 1.5 s of the second tone would take about 1.5 s of wall time.
            var position = 0L
            while (System.currentTimeMillis() - advancedAt < 1_000) {
                Thread.sleep(50)
                instrumentation.runOnMainSync { position = if (controller.currentMediaItem?.mediaId == "advance-second") controller.currentPosition else position }
                if (position >= 1_500) break
            }
            assertTrue("The pre-roll should be skipped even though it starts before the ads are loaded; position was $position", position >= 1_500)
            var firstFinished = false
            for (attempt in 0 until 20) {
                Thread.sleep(100)
                firstFinished = runBlocking { dao.episode("advance-first")?.finished == true }
                if (firstFinished) break
            }
            assertTrue("The episode that ended should be marked as played", firstFinished)
        } finally {
            instrumentation.runOnMainSync { controller.pause(); controller.clearMediaItems(); controller.release() }
            file.delete()
            runBlocking { dao.deletePodcast("advance-test") }
        }
    }

    @Test fun resumesAnEpisodeFromItsSavedPosition() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        // A 20 s tone as PCM WAV, whose duration is exact, so the saved position is inside the episode.
        val file = File(context.cacheDir, "resume-test.wav")
        writeToneWav(file, seconds = 20)
        val dao = LibraryDatabase.get(context).library()
        runBlocking {
            dao.savePodcast(Podcast("resume-test", "Test", "https://example.com"))
            dao.saveEpisode(Episode("resume-episode", "resume-test", "Tone", "https://example.com/tone", localPath = file.path, status = "READY"))
            dao.savePosition("resume-episode", 6_500, 20_000)
            dao.saveEpisode(Episode("resume-other", "resume-test", "Other", "https://example.com/other", localPath = file.path, status = "READY"))
        }
        val controller = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync().get(15, TimeUnit.SECONDS)
        try {
            instrumentation.runOnMainSync {
                controller.setMediaItems(listOf(
                    MediaItem.Builder().setMediaId("resume-other").setUri(file.toURI().toString()).build(),
                    MediaItem.Builder().setMediaId("resume-episode").setUri(file.toURI().toString()).build(),
                ))
                controller.prepare()
                controller.seekTo(1, 0)
            }
            var position = 0L
            for (attempt in 0 until 40) {
                Thread.sleep(50)
                instrumentation.runOnMainSync { position = controller.currentPosition }
                if (position >= 6_500) break
            }
            assertTrue("Playback should resume from the saved position; position was $position", position in 6_500..8_000)
        } finally {
            instrumentation.runOnMainSync { controller.clearMediaItems(); controller.release() }
            file.delete()
            runBlocking { dao.deletePodcast("resume-test") }
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
