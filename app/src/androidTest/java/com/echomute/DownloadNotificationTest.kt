package com.echomute

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DownloadNotificationTest {
    @Test fun downloadNotificationShowsProgressAndOpensApp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        }
        val dao = LibraryDatabase.get(context).library()
        val id = "notification-test-${System.nanoTime()}"
        val podcastId = "podcast-$id"
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            runBlocking {
                dao.savePodcast(Podcast(podcastId, "Test show", server.url("/feed").toString()))
                dao.saveEpisode(Episode(id, podcastId, "Test episode", server.url("/audio.mp3").toString()))
            }
            val request = OneTimeWorkRequestBuilder<EpisodeWorker>().setInputData(workDataOf("episodeId" to id)).build()
            val work = WorkManager.getInstance(context)
            val manager = context.getSystemService(NotificationManager::class.java)
            try {
                work.enqueue(request).result.get(15, TimeUnit.SECONDS)
                var notification: Notification? = null
                for (attempt in 0 until 100) {
                    notification = manager.activeNotifications.firstOrNull { it.id == id.hashCode() }?.notification
                    if (notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() == "Downloading episode") break
                    Thread.sleep(100)
                }
                assertNotNull("Download notification ${id.hashCode()} should be posted; active: ${manager.activeNotifications.joinToString { "${it.id}:${it.notification.extras.getCharSequence(Notification.EXTRA_TEXT)}" }}", notification)
                assertEquals("Downloading episode", notification!!.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString())
                assertTrue("Progress should be indeterminate when no percentage is known", notification.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE))
                assertNotNull("Tapping the notification should open the app", notification.contentIntent)
                val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
                try {
                    notification.contentIntent.send()
                    val activity = instrumentation.waitForMonitorWithTimeout(monitor, 10_000)
                    assertNotNull("Notification should launch MainActivity", activity)
                    activity?.finish()
                } finally { instrumentation.removeMonitor(monitor) }
            } finally {
                work.cancelWorkById(request.id)
                runBlocking { dao.deletePodcast(podcastId) }
                context.filesDir.resolve("episodes/$id").deleteRecursively()
            }
        }
    }
}
