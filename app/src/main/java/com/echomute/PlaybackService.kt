package com.echomute

import android.app.PendingIntent
import android.content.Intent
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
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

/** An episode resumes from its saved position only when it has progressed this far. */
private const val RESUME_THRESHOLD_MS = 5_000L

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var dao: LibraryDao
    private val currentId = MutableStateFlow<String?>(null)
    private var skipSegments: Pair<String, List<AdSegment>>? = null
    private val skipMessages = mutableListOf<PlayerMessage>()
    /** Set when a new episode starts, so an ad already underway (such as a pre-roll) is skipped even though its start was passed. */
    private var catchUpPending = false
    private var lastSavedProgress: Pair<String, Long>? = null
    /** True while a queue item is being replaced by its download, which is not a real change of episode. */
    private var swapping = false

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        dao = LibraryDatabase.get(applicationContext).library()
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        val prefs = getSharedPreferences("playback_queue", MODE_PRIVATE)
        prefs.getString("snapshot", null)?.let(::decodePlaybackQueue)?.let { saved ->
            player.setMediaItems(saved.items.map { it.toMediaItem() }, saved.index, saved.position)
            player.setPlaybackSpeed(saved.speed)
            player.prepare()
            currentId.value = player.currentMediaItem?.mediaId
            catchUpPending = true
        }
        // Replacing the media item drops pending messages, so reschedule whenever the item or timeline changes.
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val previous = currentId.value
                currentId.value = mediaItem?.mediaId
                if (swapping) return
                catchUpPending = true
                scheduleSkips()
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && previous != null) scope.launch { dao.markFinished(previous) }
                mediaItem?.mediaId?.let { resumeSavedPosition(it) }
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) scheduleSkips()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) currentId.value?.let { id -> scope.launch { dao.markFinished(id) } }
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
        scope.launch {
            currentId.flatMapLatest { id ->
                if (id == null) flowOf<Pair<String, List<AdSegment>>?>(null)
                else combine(dao.observeSegments(id), dao.observeAutoSkip(id)) { segments, autoSkip ->
                    id to if (autoSkip == true) segments.filter { it.confidence >= AUTO_SKIP_CONFIDENCE } else emptyList()
                }
            }.distinctUntilChanged().collect {
                skipSegments = it
                scheduleSkips()
            }
        }
        // Ads are found in the downloaded file, and a stream may carry different ads, so play the download once it exists.
        scope.launch {
            dao.observeDownloads().distinctUntilChanged().collect { downloads -> useDownloads(downloads) }
        }
    }

    private fun resumeSavedPosition(id: String) {
        if (player.currentPosition >= 1_000) return
        scope.launch {
            val episode = dao.episode(id) ?: return@launch
            if (episode.finished || episode.positionMs < RESUME_THRESHOLD_MS) return@launch
            // A position at the very end (or past it, if a stream and the file differ) would end the episode at once.
            if (episode.durationMs != null && episode.positionMs > episode.durationMs - RESUME_THRESHOLD_MS) return@launch
            if (player.currentMediaItem?.mediaId == id && player.currentPosition < 1_000) {
                player.seekTo(episode.positionMs)
                // The saved position may fall inside an ad, which the boundary messages would no longer catch.
                catchUpPending = true
                scheduleSkips()
            }
        }
    }

    private fun useDownloads(downloads: List<EpisodeFile>) {
        val paths = downloads.associate { it.id to it.localPath }
        for (index in 0 until player.mediaItemCount) {
            val item = player.getMediaItemAt(index)
            val scheme = item.localConfiguration?.uri?.scheme
            if (scheme != "http" && scheme != "https") continue
            val file = paths[item.mediaId]?.let(::File)?.takeIf { it.isFile } ?: continue
            val replacement = item.toQueueItem().copy(uri = file.toURI().toString()).toMediaItem()
            if (index == player.currentMediaItemIndex) {
                val position = player.currentPosition
                swapping = true
                try {
                    player.replaceMediaItem(index, replacement)
                    player.seekTo(index, position)
                } finally { swapping = false }
                scheduleSkips()
            } else {
                player.replaceMediaItem(index, replacement)
            }
        }
    }

    private fun saveQueue() {
        val prefs = getSharedPreferences("playback_queue", MODE_PRIVATE)
        if (player.mediaItemCount == 0) {
            prefs.edit().remove("snapshot").apply()
            return
        }
        val items = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).toQueueItem() }
        val snapshot = PlaybackQueueSnapshot(items, player.currentMediaItemIndex, player.currentPosition, player.playbackParameters.speed)
        prefs.edit().putString("snapshot", encodePlaybackQueue(snapshot)).apply()
        saveProgress()
    }

    /** Remembers where the current episode is, so it resumes there later and the list can show what is left. */
    private fun saveProgress() {
        if (player.playbackState == Player.STATE_ENDED) return
        val id = player.currentMediaItem?.mediaId ?: return
        val position = player.currentPosition
        val last = lastSavedProgress
        if (last != null && last.first == id && kotlin.math.abs(last.second - position) < 1_000) return
        lastSavedProgress = id to position
        val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET }
        scope.launch { dao.savePosition(id, position, duration) }
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
        if (catchUpPending) {
            catchUpPending = false
            // A message only fires when playback crosses its position. An episode that started before its ads
            // were loaded, or that resumed inside one, has already passed the start, so jump now.
            val position = player.currentPosition
            segments.firstOrNull { position >= it.startMs && position < it.endMs }?.let { segment ->
                getSharedPreferences("playback", MODE_PRIVATE).edit().putString("episode", id).putLong("skipStart", segment.startMs).apply()
                player.seekTo(segment.endMs)
            }
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
