package com.echomute

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private val currentId = MutableStateFlow<String?>(null)
    private var skipSegments: Pair<String, List<AdSegment>>? = null
    private val skipMessages = mutableListOf<PlayerMessage>()

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        // Replacing the media item drops pending messages, so reschedule whenever the item or timeline changes.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentId.value = mediaItem?.mediaId
                scheduleSkips()
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) scheduleSkips()
            }
        })
        session = MediaSession.Builder(this, player).build()
        val dao = LibraryDatabase.get(applicationContext).library()
        scope.launch {
            currentId.flatMapLatest { id ->
                if (id == null) flowOf<Pair<String, List<AdSegment>>?>(null)
                else combine(dao.observeSegments(id), dao.observeAutoSkip(id)) { segments, autoSkip ->
                    id to if (autoSkip == true) segments.filter { it.confidence >= AUTO_SKIP_CONFIDENCE } else emptyList()
                }
            }.collect {
                skipSegments = it
                scheduleSkips()
            }
        }
    }

    /** Places a player message at the start of each ad so playback jumps at the exact boundary instead of on a polling tick. */
    private fun scheduleSkips() {
        skipMessages.forEach { it.cancel() }
        skipMessages.clear()
        val id = player.currentMediaItem?.mediaId ?: return
        val (segmentsId, segments) = skipSegments ?: return
        if (segmentsId != id) return
        for (segment in segments) {
            skipMessages += player.createMessage { _, _ ->
                if (player.currentMediaItem?.mediaId == id) {
                    getSharedPreferences("playback", MODE_PRIVATE).edit().putString("episode", id).putLong("skipStart", segment.startMs).apply()
                    player.seekTo(segment.endMs)
                }
            }
                .setLooper(mainLooper)
                .setPosition(segment.startMs)
                .setDeleteAfterDelivery(false)
                .send()
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
