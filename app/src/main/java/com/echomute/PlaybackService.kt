package com.echomute

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import android.media.audiofx.LoudnessEnhancer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch

private const val RESUME_THRESHOLD_MS = 5_000L

@androidx.annotation.OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var session: MediaLibrarySession? = null
    private lateinit var player: ExoPlayer
    private lateinit var dao: LibraryDao
    private val currentId = MutableStateFlow<String?>(null)
    private var skipSegments: Pair<String, List<AdSegment>>? = null
    private val skipMessages = mutableListOf<PlayerMessage>()
    private var catchUpPending = false
    private var lastSavedProgress: Pair<String, Long>? = null
    private var swapping = false
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private lateinit var effectsPrefs: SharedPreferences
    private val effectsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        applyAudioEffects(currentId.value, applyCustomSpeed = false)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun onCreate() {
        super.onCreate()
        dao = LibraryDatabase.get(applicationContext).library()
        effectsPrefs = getSharedPreferences("audio_effects", MODE_PRIVATE)
        effectsPrefs.registerOnSharedPreferenceChangeListener(effectsListener)
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
        applyAudioEffects(currentId.value, applyCustomSpeed = false)
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                applyAudioEffects(currentId.value, applyCustomSpeed = false)
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                val previous = currentId.value
                currentId.value = mediaItem?.mediaId
                if (swapping) return
                catchUpPending = true
                scheduleSkips()
                applyAudioEffects(mediaItem?.mediaId, applyCustomSpeed = true)
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    if (previous != null) scope.launch { finishEpisode(previous) }
                    val playPrefs = getSharedPreferences("playback", MODE_PRIVATE)
                    if (playPrefs.getBoolean("sleepEndOfEpisode", false)) {
                        playPrefs.edit().putBoolean("sleepEndOfEpisode", false).apply()
                        player.pause()
                    }
                }
                mediaItem?.mediaId?.let { resumeSavedPosition(it) }
            }
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) scheduleSkips()
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    val playPrefs = getSharedPreferences("playback", MODE_PRIVATE)
                    if (playPrefs.getBoolean("sleepEndOfEpisode", false)) {
                        playPrefs.edit().putBoolean("sleepEndOfEpisode", false).apply()
                    }
                    currentId.value?.let { id -> scope.launch { finishEpisode(id) } }
                } else if (playbackState == Player.STATE_READY) {
                    scheduleSkips()
                }
            }
            override fun onEvents(player: Player, events: Player.Events) {
                if (events.containsAny(Player.EVENT_TIMELINE_CHANGED, Player.EVENT_MEDIA_ITEM_TRANSITION,
                        Player.EVENT_POSITION_DISCONTINUITY, Player.EVENT_IS_PLAYING_CHANGED,
                        Player.EVENT_PLAYBACK_PARAMETERS_CHANGED)) {
                    getSharedPreferences("playback", MODE_PRIVATE).edit().putBoolean("isPlaying", player.isPlaying).apply()
                    saveQueue()
                    UnspliceWidgetProvider.notifyChange(this@PlaybackService)
                }
            }
        })
        val openApp = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val sessionActivity = PendingIntent.getActivity(this, 0, openApp, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        session = MediaLibrarySession.Builder(this, player, LibraryCallback()).setSessionActivity(sessionActivity).build()
        scope.launch {
            val statsPrefs = getSharedPreferences("listening_stats", MODE_PRIVATE)
            val playPrefs = getSharedPreferences("playback", MODE_PRIVATE)
            while (true) {
                delay(2_000)
                if (player.isPlaying) {
                    val sleepUntil = playPrefs.getLong("sleepUntilEpochMs", 0L)
                    if (sleepUntil > 0L && System.currentTimeMillis() >= sleepUntil) {
                        playPrefs.edit().putLong("sleepUntilEpochMs", 0L).apply()
                        player.pause()
                    } else {
                        ListeningStatsStore.recordPlaybackTick(
                            statsPrefs,
                            wallElapsedMs = 2_000L,
                            speed = player.playbackParameters.speed,
                            trimSilence = player.skipSilenceEnabled
                        )
                    }
                    saveQueue()
                }
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
        scope.launch {
            dao.observeDownloads().distinctUntilChanged().collect { downloads -> useDownloads(downloads) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            UnspliceWidgetProvider.ACTION_TOGGLE_PLAY -> {
                if (player.isPlaying) player.pause() else {
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play()
                }
            }
            UnspliceWidgetProvider.ACTION_REWIND_15 -> {
                player.seekTo((player.currentPosition - 15_000L).coerceAtLeast(0L))
            }
            UnspliceWidgetProvider.ACTION_FORWARD_30 -> {
                val max = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: Long.MAX_VALUE
                player.seekTo((player.currentPosition + 30_000L).coerceAtMost(max))
            }
            UnspliceWidgetProvider.ACTION_SKIP_AD -> {
                val id = player.currentMediaItem?.mediaId
                val pos = player.currentPosition
                if (id != null) {
                    scope.launch {
                        val seg = dao.segments(id).firstOrNull { pos >= it.startMs && pos < it.endMs }
                        if (seg != null) {
                            ListeningStatsStore.recordAdSkipped(getSharedPreferences("listening_stats", MODE_PRIVATE), seg.endMs - pos)
                            player.seekTo(seg.endMs)
                        }
                    }
                }
            }
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private suspend fun finishEpisode(id: String) {
        dao.markFinished(id)
        if (effectsPrefs.getBoolean("autoDeletePlayed", false)) {
            val ep = dao.episode(id) ?: return
            if (ep.localPath != null) {
                File(filesDir, "episodes/$id").deleteRecursively()
                dao.saveEpisode(ep.copy(localPath = null, fingerprintPath = null, status = "NOT_DOWNLOADED"))
            }
        }
    }

    private fun applyAudioEffects(episodeId: String?, applyCustomSpeed: Boolean) {
        scope.launch {
            val podcast = episodeId?.let { dao.episode(it)?.podcastId }?.let { dao.podcast(it) }
            val trim = podcast?.trimSilence ?: effectsPrefs.getBoolean("trimSilence", false)
            val boost = podcast?.voiceBoost ?: effectsPrefs.getBoolean("voiceBoost", false)
            player.skipSilenceEnabled = trim
            if (applyCustomSpeed && podcast?.customSpeed != null) {
                player.setPlaybackSpeed(podcast.customSpeed)
            }
            try {
                loudnessEnhancer?.release()
                loudnessEnhancer = null
                if (boost && player.audioSessionId != C.AUDIO_SESSION_ID_UNSET && player.audioSessionId != 0) {
                    loudnessEnhancer = LoudnessEnhancer(player.audioSessionId).apply {
                        setTargetGain(600)
                        enabled = true
                    }
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun resumeSavedPosition(id: String) {
        if (player.currentPosition >= 1_000) return
        scope.launch {
            val episode = dao.episode(id) ?: return@launch
            val podcast = dao.podcast(episode.podcastId)
            val targetMs = if (!episode.finished && episode.positionMs >= RESUME_THRESHOLD_MS &&
                (episode.durationMs == null || episode.positionMs <= episode.durationMs - RESUME_THRESHOLD_MS)
            ) {
                episode.positionMs
            } else if ((podcast?.skipIntroSec ?: 0) > 0) {
                (podcast!!.skipIntroSec * 1_000L).also {
                    ListeningStatsStore.recordAdSkipped(getSharedPreferences("listening_stats", MODE_PRIVATE), it)
                }
            } else {
                return@launch
            }
            if (player.currentMediaItem?.mediaId == id && player.currentPosition < 1_000) {
                player.seekTo(targetMs)
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

    private fun scheduleSkips() {
        skipMessages.forEach { it.cancel() }
        skipMessages.clear()
        val id = player.currentMediaItem?.mediaId ?: return
        val statsPrefs = getSharedPreferences("listening_stats", MODE_PRIVATE)
        val segments = skipSegments?.takeIf { it.first == id }?.second.orEmpty()
        for (segment in segments) {
            skipMessages += player.createMessage { _, _ ->
                if (player.currentMediaItem?.mediaId == id) {
                    getSharedPreferences("playback", MODE_PRIVATE).edit().putString("episode", id).putLong("skipStart", segment.startMs).apply()
                    ListeningStatsStore.recordAdSkipped(statsPrefs, (segment.endMs - segment.startMs).coerceAtLeast(0L))
                    player.seekTo(segment.endMs)
                }
            }
                .setLooper(mainLooper)
                .setPosition(segment.startMs)
                .setDeleteAfterDelivery(false)
                .send()
        }
        if (catchUpPending && segments.isNotEmpty()) {
            catchUpPending = false
            val position = player.currentPosition
            segments.firstOrNull { position >= it.startMs && position < it.endMs }?.let { segment ->
                getSharedPreferences("playback", MODE_PRIVATE).edit().putString("episode", id).putLong("skipStart", segment.startMs).apply()
                ListeningStatsStore.recordAdSkipped(statsPrefs, (segment.endMs - position).coerceAtLeast(0L))
                player.seekTo(segment.endMs)
            }
        }
        val duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: return
        scope.launch {
            val podcast = dao.episode(id)?.let { dao.podcast(it.podcastId) } ?: return@launch
            val outroMs = podcast.skipOutroSec * 1_000L
            if (outroMs > 0L && duration > outroMs + 5_000L && player.currentMediaItem?.mediaId == id) {
                val triggerAt = duration - outroMs
                skipMessages += player.createMessage { _, _ ->
                    if (player.currentMediaItem?.mediaId == id) {
                        ListeningStatsStore.recordAdSkipped(statsPrefs, outroMs)
                        player.seekTo(duration)
                    }
                }
                    .setLooper(mainLooper)
                    .setPosition(triggerAt)
                    .setDeleteAfterDelivery(true)
                    .send()
            }
        }
    }

    private inner class LibraryCallback : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val root = MediaItem.Builder()
                .setMediaId("root")
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle("Unsplice")
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()
            return Futures.immediateFuture(LibraryResult.ofItem(root, params))
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                val items = when (parentId) {
                    "root" -> listOf(
                        browsableFolder("up_next", "Up Next"),
                        browsableFolder("new_releases", "New Releases"),
                        browsableFolder("podcasts", "Podcasts")
                    )
                    "up_next" -> (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                    "new_releases" -> {
                        val podcastsById = dao.observePodcasts().first().associateBy { it.id }
                        dao.observeAllEpisodes().first()
                            .filter { !it.finished }
                            .take(25)
                            .map { ep -> playableEpisodeItem(ep, podcastsById[ep.podcastId]?.title.orEmpty()) }
                    }
                    "podcasts" -> dao.observePodcasts().first().map { pod ->
                        browsableFolder("podcast:${pod.id}", pod.title)
                    }
                    else -> if (parentId.startsWith("podcast:")) {
                        val podId = parentId.removePrefix("podcast:")
                        val pod = dao.podcast(podId)
                        dao.episodesForPodcast(podId).map { ep -> playableEpisodeItem(ep, pod?.title.orEmpty()) }
                    } else emptyList()
                }
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(items), params))
            }
            return future
        }

        private fun browsableFolder(id: String, title: String): MediaItem =
            MediaItem.Builder()
                .setMediaId(id)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(title)
                        .setIsBrowsable(true)
                        .setIsPlayable(false)
                        .build()
                )
                .build()

        private fun playableEpisodeItem(episode: Episode, podcastTitle: String): MediaItem {
            val local = episode.localPath?.let(::File)?.takeIf { it.isFile }
            return QueueItem(
                episode.id,
                local?.toURI()?.toString() ?: episode.audioUrl,
                episode.title,
                podcastTitle,
                episode.publishedAt
            ).toMediaItem()
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        effectsPrefs.unregisterOnSharedPreferenceChangeListener(effectsListener)
        try { loudnessEnhancer?.release() } catch (_: Exception) {}
        loudnessEnhancer = null
        saveQueue()
        scope.cancel()
        session?.release()
        player.release()
        session = null
        super.onDestroy()
    }
}
