package com.echomute

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer

    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        session = MediaSession.Builder(this, player).build()
        scope.launch {
            val dao = LibraryDatabase.get(applicationContext).library()
            var cachedEpisodeId: String? = null
            var cachedSegments: List<AdSegment> = emptyList()
            var cachedAutoSkip = false
            var lastCacheSync = 0L

            while (isActive) {
                if (player.isPlaying) {
                    val id = player.currentMediaItem?.mediaId
                    val now = System.currentTimeMillis()
                    if (id != cachedEpisodeId || now - lastCacheSync > 2000L) {
                        cachedEpisodeId = id
                        lastCacheSync = now
                        val episode = id?.let { dao.episode(it) }
                        cachedAutoSkip = episode?.let { dao.podcast(it.podcastId)?.autoSkip } ?: false
                        cachedSegments = if (id != null) dao.segments(id) else emptyList()
                    }
                    if (cachedAutoSkip && cachedSegments.isNotEmpty()) {
                        val current = player.currentPosition
                        val match = cachedSegments.firstOrNull { current >= it.startMs && current < it.endMs - 500 }
                        if (match != null && id != null) {
                            getSharedPreferences("playback", MODE_PRIVATE).edit().putString("episode", id).putLong("skipStart", match.startMs).apply()
                            player.seekTo(match.endMs)
                        }
                    }
                }
                delay(250)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        player.release()
        session = null
        super.onDestroy()
    }
}
