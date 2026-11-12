package com.echomute

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT)
        )
        setContent {
            UnspliceTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    UnspliceApp()
                }
            }
        }
    }
}

@Composable
private fun UnspliceApp() {
    val context = LocalContext.current
    val dao = remember { LibraryDatabase.get(context).library() }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var pendingDownloadId by rememberSaveable { mutableStateOf<String?>(null) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        val episodeId = pendingDownloadId
        pendingDownloadId = null
        if (episodeId != null) scope.launch { dao.episode(episodeId)?.let { enqueueEpisode(context, dao, it) } }
    }

    fun startDownload(episode: Episode) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingDownloadId = episode.id
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            scope.launch { enqueueEpisode(context, dao, episode) }
        }
    }

    val podcasts by dao.observePodcasts().collectAsState(initial = emptyList())
    val allEpisodes by dao.observeAllEpisodes().collectAsState(initial = emptyList())
    val podcastsById = remember(podcasts) { podcasts.associateBy { it.id } }
    val podcastsByTitle = remember(podcasts) { podcasts.associateBy { it.title } }
    val episodesById = remember(allEpisodes) { allEpisodes.associateBy { it.id } }
    val unplayedCounts = remember(allEpisodes) { unplayedCountsByPodcast(allEpisodes) }
    val activeProgressMap by EpisodeProgressTracker.progress.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf(emptyList<PodcastSearchResult>()) }
    var searching by remember { mutableStateOf(false) }
    var feedUrl by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var podcastPendingRemoval by remember { mutableStateOf<Podcast?>(null) }
    var gridLayout by rememberSaveable { mutableStateOf(false) }
    var showStatsDialog by rememberSaveable { mutableStateOf(false) }
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var podcastSettingsOpen by rememberSaveable(selectedId) { mutableStateOf(false) }
    var transcriptDialogEpisode by remember { mutableStateOf<Pair<String, List<TranscriptLine>>?>(null) }

    val selected = selectedId?.let { podcastsById[it] }
    val episodeFlow = remember(selectedId) { selectedId?.let { dao.observeEpisodes(it) } }
    val episodes = if (episodeFlow != null) episodeFlow.collectAsState(initial = emptyList()).value else emptyList()
    val podcastSegmentsFlow = remember(selectedId) { selectedId?.let { dao.observePodcastSegments(it) } }
    val podcastSegments = if (podcastSegmentsFlow != null) podcastSegmentsFlow.collectAsState(initial = emptyList()).value else emptyList()
    val segmentsByEpisode = remember(podcastSegments) { podcastSegments.groupBy { it.episodeId } }

    var episodeFilter by rememberSaveable(selectedId) { mutableStateOf("All") }
    var episodeQuery by rememberSaveable(selectedId) { mutableStateOf("") }
    var episodeSearchOpen by rememberSaveable(selectedId) { mutableStateOf(false) }
    var expandedSegmentEpisodes by rememberSaveable(selectedId) { mutableStateOf(emptyList<String>()) }
    var expandedNotesEpisodes by rememberSaveable(selectedId) { mutableStateOf(emptyList<String>()) }
    val expandedSegmentSet = remember(expandedSegmentEpisodes) { expandedSegmentEpisodes.toSet() }
    val expandedNotesSet = remember(expandedNotesEpisodes) { expandedNotesEpisodes.toSet() }

    var smartFilter by rememberSaveable { mutableStateOf("New Releases") }
    var smartQuery by rememberSaveable { mutableStateOf("") }

    var controller by remember { mutableStateOf<MediaController?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var segments by remember { mutableStateOf(emptyList<AdSegment>()) }
    var chapters by remember { mutableStateOf(emptyList<Chapter>()) }
    var showPlayerChapters by rememberSaveable { mutableStateOf(false) }
    var showPlayerAds by rememberSaveable { mutableStateOf(false) }

    val prefs = remember { context.getSharedPreferences("playback", android.content.Context.MODE_PRIVATE) }
    val effectsPrefs = remember { context.getSharedPreferences("audio_effects", android.content.Context.MODE_PRIVATE) }
    val statsPrefs = remember { context.getSharedPreferences("listening_stats", android.content.Context.MODE_PRIVATE) }

    var trimSilence by remember { mutableStateOf(effectsPrefs.getBoolean("trimSilence", false)) }
    var voiceBoost by remember { mutableStateOf(effectsPrefs.getBoolean("voiceBoost", false)) }
    var autoDeletePlayed by remember { mutableStateOf(autoDeletePlayedEnabled(effectsPrefs)) }
    var sleepLabel by remember { mutableStateOf("Off") }
    var lastSkip by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var tab by remember { mutableStateOf("Library") }
    var playerExpanded by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf(emptyList<MediaItem>()) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var oldestFirst by rememberSaveable { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var update by remember { mutableStateOf<AppRelease?>(null) }

    LaunchedEffect(message) {
        val current = message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(current)
        if (message == current) message = null
    }

    fun mediaItem(episode: Episode, podcast: Podcast): MediaItem {
        val local = episode.localPath?.let(::File)?.takeIf { it.isFile }
        return QueueItem(episode.id, local?.toURI()?.toString() ?: episode.audioUrl, episode.title, podcast.title, episode.publishedAt).toMediaItem()
    }

    fun prepareForPlayback(episode: Episode) {
        if (episode.status !in listOf("DOWNLOADING", "QUEUED", "PROCESSING", "READY")) {
            scope.launch { enqueueEpisode(context, dao, episode) }
        }
    }

    fun playEpisode(episode: Episode, podcast: Podcast, seekToMs: Long = 0L) {
        val player = controller ?: run {
            message = "Player is starting. Try again."
            return
        }
        val existing = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == episode.id }
        val index = existing ?: player.mediaItemCount.also { player.addMediaItem(mediaItem(episode, podcast)) }
        if (seekToMs > 0L) {
            player.seekTo(index, seekToMs)
        } else if (index != player.currentMediaItemIndex || player.playbackState == Player.STATE_ENDED) {
            player.seekTo(index, 0)
        }
        player.prepare()
        player.play()
        prepareForPlayback(episode)
    }

    fun addToQueue(episode: Episode, podcast: Podcast) {
        val player = controller ?: run {
            message = "Player is starting. Try again."
            return
        }
        val existing = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == episode.id }
        if (existing == null || existing < player.currentMediaItemIndex) {
            if (existing != null) player.removeMediaItem(existing)
            player.addMediaItem(mediaItem(episode, podcast))
            prepareForPlayback(episode)
            message = "Added to Up Next"
        } else {
            message = "Already in Up Next"
        }
    }

    fun toggleEpisodeFinished(episode: Episode) {
        scope.launch {
            if (!episode.finished) {
                try {
                    WorkManager.getInstance(context).cancelUniqueWork("episode-${episode.id}")
                } catch (_: Exception) {}
                withContext(Dispatchers.IO) {
                    finishEpisodeAndCleanup(dao, context.filesDir, episode, autoDeletePlayed)
                }
                if (autoDeletePlayed && !episode.starred) {
                    controller?.let { player ->
                        for (idx in player.mediaItemCount - 1 downTo 0) {
                            if (player.getMediaItemAt(idx).mediaId == episode.id) {
                                if (idx == player.currentMediaItemIndex) {
                                    val item = player.getMediaItemAt(idx)
                                    if (item.localConfiguration?.uri?.scheme == "file") {
                                        player.replaceMediaItem(idx, item.toQueueItem().copy(uri = episode.audioUrl).toMediaItem())
                                    }
                                } else {
                                    player.removeMediaItem(idx)
                                }
                            }
                        }
                    }
                }
            } else {
                dao.setFinished(episode.id, false)
            }
        }
    }

    fun removePastItemsIfSafe(ids: Set<String>): Boolean {
        val player = controller ?: return false
        val current = player.currentMediaItemIndex.coerceAtLeast(0)
        if ((current until player.mediaItemCount).any { player.getMediaItemAt(it).mediaId in ids }) return false
        for (index in (current - 1) downTo 0) {
            if (player.getMediaItemAt(index).mediaId in ids) player.removeMediaItem(index)
        }
        return true
    }

    BackHandler(enabled = !playerExpanded && (selectedId != null || tab != "Library")) {
        if (selectedId != null) selectedId = null else tab = "Library"
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            DefaultSubscription.ensureDefaultSubscribed(context, dao)
            DefaultSubscription.refreshMissingArtwork(context, dao)
            DefaultSubscription.refreshFeeds(context, dao)
        }
        update = checkForUpdate(context)
    }

    LaunchedEffect(podcasts, allEpisodes) {
        val autoDownloadPodcastIds = podcasts.filter { it.autoDownload }.map { it.id }.toSet()
        if (autoDownloadPodcastIds.isNotEmpty()) {
            allEpisodes
                .filter { it.podcastId in autoDownloadPodcastIds && !it.finished && it.status == "NOT_DOWNLOADED" }
                .take(2)
                .forEach { enqueueEpisode(context, dao, it) }
        }
    }

    DisposableEffect(context) {
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        future.addListener({
            if (!future.isCancelled) runCatching { future.get() }.onSuccess { player ->
                controller = player
                playing = player.isPlaying
                queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) { message = "Playback failed: ${error.message ?: "Try again"}" }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        playing = player.isPlaying
                        duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0
                    }
                    override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                        queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                        currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        currentId = mediaItem?.mediaId
                        currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
                    }
                })
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { MediaController.releaseFuture(future); controller = null }
    }

    LaunchedEffect(currentId) {
        val id = currentId
        chapters = if (id == null) emptyList() else withContext(Dispatchers.IO) {
            readChapters(File(context.filesDir, "episodes/$id/chapters.tsv"))
        }
    }

    LaunchedEffect(controller) {
        while (true) {
            controller?.let { player ->
                position = player.currentPosition
                duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0
                playing = player.isPlaying
                currentId = player.currentMediaItem?.mediaId
                queue = (0 until player.mediaItemCount).map { player.getMediaItemAt(it) }
                currentIndex = player.currentMediaItemIndex.coerceAtLeast(0)
                speed = player.playbackParameters.speed
                segments = currentId?.let { dao.segments(it) } ?: emptyList()
                lastSkip = prefs.getString("episode", null)
                val sleepUntil = prefs.getLong("sleepUntilEpochMs", 0L)
                val sleepEoe = prefs.getBoolean("sleepEndOfEpisode", false)
                sleepLabel = when {
                    sleepEoe -> "End of ep"
                    sleepUntil > System.currentTimeMillis() -> "${((sleepUntil - System.currentTimeMillis()) / 60_000L + 1)}m"
                    else -> "Off"
                }
            }
            delay(500)
        }
    }

    suspend fun subscribeUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        loading = true
        message = null
        try {
            DefaultSubscription.subscribeFeed(trimmed, dao)
            feedUrl = ""
            searchResults = emptyList()
            message = "Subscribed"
        } catch (e: Exception) {
            message = e.message ?: "Could not load the feed"
        } finally {
            loading = false
        }
    }

    fun runPodcastSearch() {
        if (searching || searchQuery.isBlank()) return
        scope.launch {
            searching = true
            message = null
            try {
                val results = withContext(Dispatchers.IO) { searchPodcasts(searchQuery) }
                searchResults = results
                if (results.isEmpty()) message = "No podcasts found"
            } catch (e: Exception) {
                message = e.message ?: "Search failed"
            } finally {
                searching = false
            }
        }
    }

    fun openTranscriptFor(episode: Episode) {
        scope.launch {
            val file = File(context.filesDir, "episodes/${episode.id}/transcript.tsv")
            val words = withContext(Dispatchers.IO) { if (file.isFile) readTranscript(file) else emptyList() }
            val grouped = groupTranscriptLines(words)
            if (grouped.isEmpty()) {
                message = "No transcript available for this episode."
            } else {
                transcriptDialogEpisode = episode.id to grouped
            }
        }
    }

    fun togglePlayPause() {
        controller?.let { player ->
            if (player.isPlaying) player.pause() else {
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }
        }
    }

    fun skipAdSegment(ad: AdSegment) {
        ListeningStatsStore.recordAdSkipped(statsPrefs, (ad.endMs - position).coerceAtLeast(0L))
        controller?.seekTo(ad.endMs)
        position = ad.endMs
    }

    fun undoLastSkip() {
        val id = currentId
        scope.launch {
            val start = prefs.getLong("skipStart", 0)
            if (id != null) dao.segments(id).filter { it.startMs == start }.forEach { dao.deleteSegment(it.id) }
            controller?.seekTo(start + 1)
            prefs.edit { remove("episode") }
            lastSkip = null
        }
    }

    transcriptDialogEpisode?.let { (epId, lines) ->
        val epTitle = episodesById[epId]?.title ?: "Episode"
        TranscriptDialog(
            episodeTitle = epTitle,
            lines = lines,
            currentPositionMs = if (currentId == epId) position else -1L,
            onSeek = { targetMs ->
                if (currentId == epId) {
                    controller?.seekTo(targetMs)
                    position = targetMs
                }
            },
            onMarkAd = { line ->
                scope.launch {
                    dao.saveSegments(
                        listOf(
                            AdSegment(
                                episodeId = epId,
                                startMs = line.startMs,
                                endMs = (line.endMs + 2_000L).coerceAtLeast(line.startMs + 3_000L),
                                source = "transcript",
                                confidence = 0.95f
                            )
                        )
                    )
                    message = "Marked ${formatTime(line.startMs)} as ad segment"
                }
            },
            onDismiss = { transcriptDialogEpisode = null }
        )
    }

    if (showStatsDialog) {
        val stats = remember(showStatsDialog, position) { ListeningStatsStore.read(statsPrefs) }
        ListeningStatsDialog(stats = stats, onDismiss = { showStatsDialog = false })
    }

    if (showSettingsDialog) {
        val storageList = remember(showSettingsDialog, podcasts, allEpisodes) {
            calculatePodcastStorage(context.filesDir, podcasts, allEpisodes)
        }
        SettingsDialog(
            trimSilence = trimSilence,
            voiceBoost = voiceBoost,
            autoDeletePlayed = autoDeletePlayed,
            storageList = storageList,
            onTrimSilenceChange = {
                trimSilence = it
                effectsPrefs.edit { putBoolean("trimSilence", it) }
            },
            onVoiceBoostChange = {
                voiceBoost = it
                effectsPrefs.edit { putBoolean("voiceBoost", it) }
            },
            onAutoDeleteChange = {
                autoDeletePlayed = it
                effectsPrefs.edit { putBoolean(AUTO_DELETE_PLAYED_KEY, it) }
            },
            onClearPodcastStorage = { usage ->
                scope.launch {
                    val eps = dao.episodesForPodcast(usage.podcastId)
                    eps.forEach { ep ->
                        File(context.filesDir, "episodes/${ep.id}").deleteRecursively()
                        if (ep.localPath != null) {
                            dao.saveEpisode(ep.copy(localPath = null, fingerprintPath = null, status = "NOT_DOWNLOADED"))
                        }
                    }
                    showSettingsDialog = false
                }
            },
            onDismiss = { showSettingsDialog = false }
        )
    }

    podcastPendingRemoval?.let { targetPodcast ->
        RemovePodcastDialog(
            podcast = targetPodcast,
            onConfirm = {
                podcastPendingRemoval = null
                scope.launch {
                    val podEpisodes = dao.episodesForPodcast(targetPodcast.id)
                    if (!removePastItemsIfSafe(podEpisodes.map { it.id }.toSet())) {
                        message = "Remove episodes for this podcast from Up Next before removing it."
                    } else {
                        podEpisodes.forEach { File(context.filesDir, "episodes/${it.id}").deleteRecursively() }
                        dao.deletePodcast(targetPodcast.id)
                        if (selectedId == targetPodcast.id) selectedId = null
                    }
                }
            },
            onDismiss = { podcastPendingRemoval = null }
        )
    }

    val upcoming = remember(queue, currentIndex) { queue.drop(currentIndex) }
    val currentTitle = controller?.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty()
    val currentArtist = controller?.currentMediaItem?.mediaMetadata?.artist?.toString().orEmpty()
    val playingEpisode = currentId?.let { episodesById[it] }
    val playingPodcast = playingEpisode?.let { podcastsById[it.podcastId] } ?: podcastsByTitle[currentArtist]
    val currentAutoSkip = playingPodcast?.autoSkip ?: true
    val currentAd = segments.firstOrNull { position >= it.startMs && position < it.endMs }
    val showUndoSkip = lastSkip == currentId && currentId != null

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = {
                SnackbarHost(snackbarHostState) { data ->
                    Snackbar(
                        snackbarData = data,
                        containerColor = UnspliceSurfaceBright,
                        contentColor = UnspliceIvory,
                        actionColor = UnspliceMint
                    )
                }
            },
            bottomBar = {
                Column {
                    if (currentId != null && controller != null && !playerExpanded) {
                        MiniPlayerBar(
                            title = currentTitle,
                            artistName = currentArtist,
                            artworkUrl = playingPodcast?.artworkUrl,
                            playing = playing,
                            position = position,
                            duration = duration,
                            segments = segments,
                            autoSkip = currentAutoSkip,
                            currentAd = currentAd,
                            showUndoSkip = showUndoSkip,
                            onTogglePlayPause = ::togglePlayPause,
                            onExpand = { playerExpanded = true },
                            onSkipAd = ::skipAdSegment,
                            onUndoSkip = ::undoLastSkip
                        )
                    }
                    HorizontalDivider(color = UnspliceOutline, thickness = 1.dp)
                    NavigationBar(containerColor = UnspliceSurface, tonalElevation = 0.dp) {
                        listOf(
                            Triple("Library", Icons.Filled.LibraryMusic, "Library"),
                            Triple("Filters", Icons.Filled.FilterList, "Filters"),
                            Triple("Discover", Icons.Filled.Search, "Discover"),
                            Triple("Up Next", Icons.AutoMirrored.Filled.QueueMusic, "Up Next")
                        ).forEach { (label, icon, destination) ->
                            val isSelected = tab == destination
                            NavigationBarItem(
                                selected = isSelected,
                                onClick = { tab = destination; selectedId = null },
                                icon = {
                                    if (destination == "Up Next" && upcoming.isNotEmpty()) {
                                        BadgedBox(
                                            badge = {
                                                Badge(containerColor = UnspliceMint, contentColor = UnspliceOnMint) {
                                                    Text("${upcoming.size}")
                                                }
                                            }
                                        ) {
                                            Icon(icon, contentDescription = null)
                                        }
                                    } else {
                                        Icon(icon, contentDescription = null)
                                    }
                                },
                                label = { Text(label, fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = UnspliceMint,
                                    selectedTextColor = UnspliceMint,
                                    indicatorColor = UnspliceMintContainer,
                                    unselectedIconColor = UnspliceSlate,
                                    unselectedTextColor = UnspliceSlate
                                )
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                when {
                    tab == "Up Next" -> {
                        QueueTab(
                            upcoming = upcoming,
                            currentId = currentId,
                            podcastsByTitle = podcastsByTitle,
                            onPlayItem = { item ->
                                controller?.let { player ->
                                    val target = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == item.mediaId }
                                    if (target != null) {
                                        if (target != player.currentMediaItemIndex || player.playbackState == Player.STATE_ENDED) player.seekTo(target, 0)
                                        if (player.playbackState == Player.STATE_IDLE) player.prepare()
                                        player.play()
                                    }
                                }
                            },
                            onMoveUp = { idx -> controller?.moveMediaItem(currentIndex + idx, currentIndex + idx - 1) },
                            onMoveDown = { idx -> controller?.moveMediaItem(currentIndex + idx, currentIndex + idx + 1) },
                            onFinishItem = { idx, item ->
                                scope.launch {
                                    val ep = dao.episode(item.mediaId)
                                    controller?.removeMediaItem(currentIndex + idx)
                                    if (ep != null) {
                                        try {
                                            WorkManager.getInstance(context).cancelUniqueWork("episode-${ep.id}")
                                        } catch (_: Exception) {}
                                        withContext(Dispatchers.IO) {
                                            finishEpisodeAndCleanup(dao, context.filesDir, ep, autoDeletePlayed)
                                        }
                                    } else {
                                        dao.markFinished(item.mediaId)
                                    }
                                }
                            },
                            onRemoveItem = { idx -> controller?.removeMediaItem(currentIndex + idx) }
                        )
                    }
                    tab == "Filters" -> {
                        val filteredSmart = remember(allEpisodes, smartFilter, smartQuery) {
                            filterSmartEpisodes(allEpisodes, smartFilter, smartQuery)
                        }
                        FiltersTab(
                            smartFilter = smartFilter,
                            smartQuery = smartQuery,
                            episodes = filteredSmart,
                            podcastsById = podcastsById,
                            onSelectFilter = { smartFilter = it },
                            onQueryChange = { smartQuery = it },
                            onToggleStar = { ep -> scope.launch { dao.setStarred(ep.id, !ep.starred) } },
                            onToggleFinished = ::toggleEpisodeFinished,
                            onPlayEpisode = { ep, pod -> playEpisode(ep, pod) }
                        )
                    }
                    tab == "Discover" -> {
                        DiscoverTab(
                            searchQuery = searchQuery,
                            searching = searching,
                            searchResults = searchResults,
                            feedUrl = feedUrl,
                            loading = loading,
                            podcasts = podcasts,
                            onSearchQueryChange = { searchQuery = it },
                            onRunSearch = ::runPodcastSearch,
                            onFeedUrlChange = { feedUrl = it },
                            onSubscribeFeed = { url -> scope.launch { subscribeUrl(url) } },
                            onOpenSubscribed = { pod ->
                                selectedId = pod.id
                                tab = "Library"
                            }
                        )
                    }
                    selected == null -> {
                        val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
                        LibraryTab(
                            version = version,
                            podcasts = podcasts,
                            unplayedCounts = unplayedCounts,
                            gridLayout = gridLayout,
                            update = update,
                            onToggleLayout = { gridLayout = !gridLayout },
                            onOpenStats = { showStatsDialog = true },
                            onOpenSettings = { showSettingsDialog = true },
                            onDownloadUpdate = { release ->
                                context.startActivity(Intent(Intent.ACTION_VIEW, release.apkUrl.toUri()))
                            },
                            onSelectPodcast = { selectedId = it },
                            onRequestRemovePodcast = { podcastPendingRemoval = it },
                            onOpenDiscover = { tab = "Discover" }
                        )
                    }
                    else -> {
                        val visibleEpisodes = remember(episodes, episodeQuery, episodeFilter, oldestFirst) {
                            filterEpisodes(episodes, episodeQuery, episodeFilter, oldestFirst)
                        }
                        ShowDetailScreen(
                            podcast = selected,
                            episodes = episodes,
                            visibleEpisodes = visibleEpisodes,
                            unplayedCount = unplayedCounts[selected.id] ?: 0,
                            segmentsByEpisode = segmentsByEpisode,
                            activeProgressMap = activeProgressMap,
                            filesDir = context.filesDir,
                            refreshing = refreshing,
                            podcastSettingsOpen = podcastSettingsOpen,
                            episodeFilter = episodeFilter,
                            episodeQuery = episodeQuery,
                            episodeSearchOpen = episodeSearchOpen,
                            oldestFirst = oldestFirst,
                            expandedSegmentSet = expandedSegmentSet,
                            expandedNotesSet = expandedNotesSet,
                            onBack = { selectedId = null },
                            onRefresh = {
                                scope.launch {
                                    refreshing = true
                                    try {
                                        DefaultSubscription.subscribeFeed(selected.feedUrl, dao)
                                    } catch (e: Exception) {
                                        message = e.message ?: "Could not refresh the feed"
                                    } finally {
                                        refreshing = false
                                    }
                                }
                            },
                            onTogglePodcastSettings = { podcastSettingsOpen = !podcastSettingsOpen },
                            onUpdatePodcast = { updated -> scope.launch { dao.savePodcast(updated) } },
                            onRequestRemovePodcast = { podcastPendingRemoval = selected },
                            onFilterChange = { episodeFilter = it },
                            onToggleSearch = {
                                episodeSearchOpen = !episodeSearchOpen
                                if (!episodeSearchOpen) episodeQuery = ""
                            },
                            onQueryChange = { episodeQuery = it },
                            onToggleSort = { oldestFirst = !oldestFirst },
                            onToggleNotes = { id ->
                                expandedNotesEpisodes = if (id in expandedNotesSet) expandedNotesEpisodes - id else expandedNotesEpisodes + id
                            },
                            onToggleSegments = { id ->
                                expandedSegmentEpisodes = if (id in expandedSegmentSet) expandedSegmentEpisodes - id else expandedSegmentEpisodes + id
                            },
                            onToggleStar = { ep -> scope.launch { dao.setStarred(ep.id, !ep.starred) } },
                            onSeekTimestamp = { ep, ms -> playEpisode(ep, selected, seekToMs = ms) },
                            onKeepSegment = { seg -> scope.launch { dao.deleteSegment(seg.id) } },
                            onScanAgain = ::startDownload,
                            onOpenTranscript = ::openTranscriptFor,
                            onPlayEpisode = { ep -> playEpisode(ep, selected) },
                            onDownloadEpisode = ::startDownload,
                            onCancelEpisode = { ep -> scope.launch { cancelEpisode(context, dao, ep) } },
                            onAddToQueue = { ep -> addToQueue(ep, selected) },
                            onDeleteDownload = { ep ->
                                scope.launch {
                                    if (!removePastItemsIfSafe(setOf(ep.id))) {
                                        message = "Remove this episode from Up Next before deleting its download."
                                    } else {
                                        dao.saveEpisode(withContext(Dispatchers.IO) { deleteEpisodeDownload(context.filesDir, ep) })
                                        dao.deleteSegments(ep.id)
                                    }
                                }
                            },
                            onToggleFinished = ::toggleEpisodeFinished
                        )
                    }
                }
            }
        }

        val hasTranscript = currentId != null && File(context.filesDir, "episodes/$currentId/transcript.tsv").isFile
        NowPlayingSheet(
            visible = playerExpanded && currentId != null && controller != null,
            title = currentTitle,
            artistName = currentArtist,
            artworkUrl = playingPodcast?.artworkUrl,
            playing = playing,
            position = position,
            duration = duration,
            speed = speed,
            trimSilence = trimSilence,
            voiceBoost = voiceBoost,
            sleepLabel = sleepLabel,
            segments = segments,
            chapters = chapters,
            autoSkip = currentAutoSkip,
            canToggleAutoSkip = playingPodcast != null,
            episodeStatus = playingEpisode?.status,
            episodeProgress = currentId?.let { activeProgressMap[it] },
            showPlayerChapters = showPlayerChapters,
            showPlayerAds = showPlayerAds,
            hasTranscript = hasTranscript,
            currentAd = currentAd,
            showUndoSkip = showUndoSkip,
            onCollapse = { playerExpanded = false },
            onOpenShow = playingPodcast?.let { pod ->
                {
                    playerExpanded = false
                    selectedId = pod.id
                    tab = "Library"
                }
            },
            onTogglePlayPause = ::togglePlayPause,
            onSeek = { target ->
                controller?.seekTo(target)
                position = target
            },
            onStepSpeed = { delta ->
                speed = stepSpeed(speed, delta)
                controller?.setPlaybackSpeed(speed)
            },
            onCycleSpeed = {
                speed = nextSpeedPreset(speed)
                controller?.setPlaybackSpeed(speed)
            },
            onToggleTrimSilence = {
                trimSilence = !trimSilence
                effectsPrefs.edit { putBoolean("trimSilence", trimSilence) }
            },
            onToggleVoiceBoost = {
                voiceBoost = !voiceBoost
                effectsPrefs.edit { putBoolean("voiceBoost", voiceBoost) }
            },
            onCycleSleepTimer = {
                val now = System.currentTimeMillis()
                val next = nextSleepTimerUpdate(
                    nowMs = now,
                    currentUntilMs = prefs.getLong("sleepUntilEpochMs", 0L),
                    currentEndOfEpisode = prefs.getBoolean("sleepEndOfEpisode", false)
                )
                prefs.edit {
                    putLong("sleepUntilEpochMs", next.untilEpochMs)
                    putBoolean("sleepEndOfEpisode", next.endOfEpisode)
                }
                sleepLabel = next.label
            },
            onToggleAutoSkip = {
                playingPodcast?.let { pod ->
                    scope.launch { dao.savePodcast(pod.copy(autoSkip = !pod.autoSkip)) }
                }
            },
            onToggleChapters = { showPlayerChapters = !showPlayerChapters },
            onToggleAdsPanel = { showPlayerAds = !showPlayerAds },
            onOpenTranscript = { playingEpisode?.let(::openTranscriptFor) },
            onToggleChapterSkip = { ch, chEnd, skippedSeg ->
                val id = currentId ?: return@NowPlayingSheet
                scope.launch {
                    if (skippedSeg != null) {
                        dao.deleteSegment(skippedSeg.id)
                    } else if (chEnd > ch.startMs) {
                        dao.saveSegments(listOf(AdSegment(episodeId = id, startMs = ch.startMs, endMs = chEnd, source = "chapter", confidence = 0.9f)))
                    }
                }
            },
            onKeepSegment = { seg -> scope.launch { dao.deleteSegment(seg.id) } },
            onScanAds = { playingEpisode?.let(::startDownload) },
            onSkipAd = ::skipAdSegment,
            onUndoSkip = ::undoLastSkip
        )
    }
}

private suspend fun enqueueEpisode(context: android.content.Context, dao: LibraryDao, episode: Episode) {
    dao.deleteSegments(episode.id)
    dao.saveEpisode(episode.copy(status = "QUEUED", error = null))
    val request = OneTimeWorkRequestBuilder<EpisodeWorker>().setInputData(workDataOf("episodeId" to episode.id)).build()
    WorkManager.getInstance(context).enqueueUniqueWork("episode-${episode.id}", ExistingWorkPolicy.KEEP, request)
}

private suspend fun cancelEpisode(context: android.content.Context, dao: LibraryDao, episode: Episode) {
    WorkManager.getInstance(context).cancelUniqueWork("episode-${episode.id}")
    EpisodeProgressTracker.clear(episode.id)
    val downloaded = episode.localPath?.let(::File)?.isFile == true
    dao.saveEpisode(episode.copy(status = if (downloaded) "READY" else "NOT_DOWNLOADED", error = null))
}
