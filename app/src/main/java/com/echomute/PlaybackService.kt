package com.echomute

import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
import kotlinx.coroutines.delay
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
        val prefs = getSharedPreferences("playback_queue", MODE_PRIVATE)
        prefs.getString("snapshot", null)?.let(::decodePlaybackQueue)?.let { saved ->
            val items = saved.items.map { item ->
                MediaItem.Builder().setMediaId(item.id).setUri(item.uri)
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).setArtist(item.artist).build()).build()
            }
            player.setMediaItems(items, saved.index, saved.position)
            player.setPlaybackSpeed(saved.speed)
            player.prepare()
            currentId.value = player.currentMediaItem?.mediaId
        }
        // Replacing the media item drops pending messages, so reschedule whenever the item or timeline changes.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentId.value = mediaItem?.mediaId
                scheduleSkips()
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) scheduleSkips()
            }
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) saveQueue()
            }
        })
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivity = PendingIntent.getActivity(this, 0, openApp, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaSession.Builder(this, player).setSessionActivity(sessionActivity).build()
        scope.launch {
            while (true) {
                delay(2_000)
                if (player.isPlaying) saveQueue()
            }
        }
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

    private fun saveQueue() {
        val prefs = getSharedPreferences("playback_queue", MODE_PRIVATE)
        if (player.mediaItemCount == 0) {
            prefs.edit().remove("snapshot").apply()
            return
        }
        val items = (0 until player.mediaItemCount).map { index ->
            val item = player.getMediaItemAt(index)
            QueueItem(item.mediaId, item.localConfiguration?.uri?.toString().orEmpty(),
                item.mediaMetadata.title?.toString().orEmpty(), item.mediaMetadata.artist?.toString().orEmpty())
        }
        val snapshot = PlaybackQueueSnapshot(items, player.currentMediaItemIndex, player.currentPosition, player.playbackParameters.speed)
        prefs.edit().putString("snapshot", encodePlaybackQueue(snapshot)).apply()
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
        saveQueue()
        scope.cancel()
        session?.release()
        player.release()
        session = null
        super.onDestroy()
    }
}
