package com.echomute

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the MediaController connection and exposes playback state as observable flows.
 * UI code should only read these flows and call methods here - it never reads the
 * controller on a timer, so the queue and preferences are no longer polled twice a second.
 */
internal data class PlaybackFailure(val message: String, val mediaId: String?)

@OptIn(ExperimentalCoroutinesApi::class)
internal class PlaybackStateHolder(context: Context) {
    private val appContext = context.applicationContext
    private val dao = LibraryDatabase.get(appContext).library()
    private val playbackPrefs = appContext.getSharedPreferences("playback", Context.MODE_PRIVATE)
    private val effectsPrefs = appContext.getSharedPreferences("audio_effects", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val controllerState = MutableStateFlow<MediaController?>(null)
    val controller: StateFlow<MediaController?> = controllerState

    private val positionState = MutableStateFlow(0L)
    val position: StateFlow<Long> = positionState
    private val durationState = MutableStateFlow(0L)
    val duration: StateFlow<Long> = durationState
    private val playingState = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = playingState
    private val currentIdState = MutableStateFlow<String?>(null)
    val currentId: StateFlow<String?> = currentIdState
    private val queueState = MutableStateFlow<List<MediaItem>>(emptyList())
    val queue: StateFlow<List<MediaItem>> = queueState
    private val currentIndexState = MutableStateFlow(0)
    val currentIndex: StateFlow<Int> = currentIndexState
    private val speedState = MutableStateFlow(1f)
    val speed: StateFlow<Float> = speedState
    private val errorState = MutableStateFlow<PlaybackFailure?>(null)
    val error: StateFlow<PlaybackFailure?> = errorState

    val segments: StateFlow<List<AdSegment>> = currentId
        .flatMapLatest { id -> id?.let(dao::observeSegments) ?: flowOf(emptyList()) }
        .stateIn(scope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val lastSkip: StateFlow<String?> = playbackPrefs.stringState("episode")
    private val sleepUntil = playbackPrefs.longState("sleepUntilEpochMs", 0L)
    private val sleepEndOfEpisode = playbackPrefs.booleanState("sleepEndOfEpisode", false)
    val sleepLabel: StateFlow<String> = combine(sleepUntil, sleepEndOfEpisode, clock()) { until, endOfEpisode, _ ->
        formatSleepLabel(until, endOfEpisode)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5_000), "Off")

    val trimSilence: StateFlow<Boolean> = effectsPrefs.booleanState("trimSilence", false)
    val voiceBoost: StateFlow<Boolean> = effectsPrefs.booleanState("voiceBoost", false)
    val autoDeletePlayed: StateFlow<Boolean> = effectsPrefs
        .keyFlow("autoDeletePlayed")
        .map { autoDeletePlayedEnabled(effectsPrefs) }
        .stateIn(scope, SharingStarted.Eagerly, autoDeletePlayedEnabled(effectsPrefs))

    private var connectFuture: com.google.common.util.concurrent.ListenableFuture<MediaController>? = null
    private var ticker: Job? = null
    private var tickerOwner: MediaController? = null

    fun connect() {
        val future = MediaController.Builder(
            appContext,
            SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        ).buildAsync()
        connectFuture = future
        future.addListener({
            if (!future.isCancelled) {
                runCatching { future.get() }
                    .onSuccess(::attach)
                    .onFailure { error ->
                        AppLog.w(TAG, "Could not connect to the playback service", error)
                        errorState.value = PlaybackFailure("Could not connect to the player", null)
                    }
            }
        }, ContextCompat.getMainExecutor(appContext))
    }

    fun release() {
        ticker?.cancel()
        ticker = null
        tickerOwner = null
        connectFuture?.let(MediaController::releaseFuture)
        connectFuture = null
        controllerState.value = null
        scope.cancel()
    }

    fun clearError() {
        errorState.value = null
    }

    private fun attach(player: MediaController) {
        if (!scope.isActive) return
        controllerState.value = player
        sync(player)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                playingState.value = isPlaying
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                playingState.value = player.isPlaying
                durationState.value = validDuration(player)
            }

            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                syncQueue(player)
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                currentIdState.value = mediaItem?.mediaId
                currentIndexState.value = player.currentMediaItemIndex.coerceAtLeast(0)
            }

            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
                speedState.value = playbackParameters.speed
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                positionState.value = player.currentPosition
            }

            override fun onPlayerError(error: PlaybackException) {
                AppLog.e(TAG, "Playback failed (${error.errorCode})", error)
                errorState.value = PlaybackFailure(
                    message = "Playback failed: ${error.message ?: "Try again"}",
                    mediaId = player.currentMediaItem?.mediaId
                )
            }
        })
        startTicker(player)
    }

    private fun sync(player: MediaController) {
        playingState.value = player.isPlaying
        durationState.value = validDuration(player)
        currentIdState.value = player.currentMediaItem?.mediaId
        speedState.value = player.playbackParameters.speed
        positionState.value = player.currentPosition
        syncQueue(player)
    }

    private fun syncQueue(player: MediaController) {
        queueState.value = (0 until player.mediaItemCount).map(player::getMediaItemAt)
        currentIndexState.value = player.currentMediaItemIndex.coerceAtLeast(0)
    }

    /** Position is the only value with no Media3 callback, so it needs a short ticker. */
    private fun startTicker(player: MediaController) {
        ticker?.cancel()
        tickerOwner = player
        ticker = scope.launch {
            playingState.collect { isPlaying ->
                if (tickerOwner !== player) return@collect
                if (isPlaying) {
                    while (isActive && playingState.value) {
                        positionState.value = player.currentPosition
                        delay(250L)
                    }
                } else {
                    positionState.value = player.currentPosition
                }
            }
        }
    }

    private fun validDuration(player: MediaController): Long =
        player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0L

    private fun clock(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(30_000L)
        }
    }

    private fun SharedPreferences.keyFlow(vararg keys: String): Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == null || changed in keys) trySend(Unit)
        }
        registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun SharedPreferences.stringState(key: String, default: String? = null): StateFlow<String?> =
        keyFlow(key).map { getString(key, default) }
            .stateIn(scope, SharingStarted.Eagerly, getString(key, default))

    private fun SharedPreferences.longState(key: String, default: Long): StateFlow<Long> =
        keyFlow(key).map { getLong(key, default) }
            .stateIn(scope, SharingStarted.Eagerly, getLong(key, default))

    private fun SharedPreferences.booleanState(key: String, default: Boolean): StateFlow<Boolean> =
        keyFlow(key).map { getBoolean(key, default) }
            .stateIn(scope, SharingStarted.Eagerly, getBoolean(key, default))

    private companion object {
        const val TAG = "PlaybackStateHolder"
    }
}
