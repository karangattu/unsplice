package com.echomute

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

fun shouldDeferAnalysis(charging: Boolean, audioPlaying: Boolean) = !charging && audioPlaying

class EpisodeWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val id = inputData.getString("episodeId") ?: return Result.failure()
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("downloads", "Podcast downloads", NotificationManager.IMPORTANCE_LOW))
        val openApp = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        fun notification(text: String) = ForegroundInfo(
            id.hashCode(),
            NotificationCompat.Builder(applicationContext, "downloads")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Preparing podcast")
                .setContentText(text)
                .setContentIntent(openApp)
                .setProgress(0, 0, true)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .setOngoing(true).build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        setForeground(notification("Getting episode ready"))
        return try {
            val dao = LibraryDatabase.get(applicationContext).library()
            coroutineScope {
                val observer = launch {
                    try {
                        val podcastId = dao.episode(id)?.podcastId ?: return@launch
                        dao.observeEpisodes(podcastId)
                            .map { episodes -> episodes.firstOrNull { it.id == id }?.status }
                            .distinctUntilChanged()
                            .collect { status ->
                                val text = when (status) {
                                    "DOWNLOADING" -> "Downloading episode"
                                    "PROCESSING" -> "Finding ads"
                                    else -> null
                                }
                                if (text != null) setForeground(notification(text))
                            }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // A notification update must not interrupt the download.
                    }
                }
                try { processEpisode(applicationContext, dao, id) }
                finally { observer.cancelAndJoin() }
            }
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: java.io.IOException) {
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        } catch (e: Exception) { Result.failure() }
    }
}
