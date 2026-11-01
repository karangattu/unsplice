package com.echomute

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.work.*
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private val PocketCastsRed = Color(0xFFEE3838)
private val PocketCastsDark = Color(0xFF121316)
private val PocketCastsSurface = Color(0xFF1E1F24)
private val PocketCastsSurfaceVariant = Color(0xFF282930)
private val PocketCastsAdGold = Color(0xFFFFB300)

private val PocketCastsColorScheme = darkColorScheme(
    primary = PocketCastsRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF381212),
    onPrimaryContainer = Color(0xFFFFDAD6),
    secondary = Color(0xFFE0E0E6),
    onSecondary = PocketCastsDark,
    secondaryContainer = PocketCastsSurfaceVariant,
    onSecondaryContainer = Color(0xFFE0E0E6),
    background = PocketCastsDark,
    onBackground = Color(0xFFF0F0F5),
    surface = PocketCastsSurface,
    onSurface = Color(0xFFF0F0F5),
    surfaceVariant = PocketCastsSurfaceVariant,
    onSurfaceVariant = Color(0xFF9E9EB0),
    outline = Color(0xFF3A3B45),
    error = Color(0xFFFF5449),
    onError = Color.White
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = PocketCastsColorScheme) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    LibraryScreen()
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return if (min >= 60) "%d:%02d:%02d".format(min / 60, min % 60, sec) else "%02d:%02d".format(min, sec)
}

@Composable
internal fun PlaybackProgress(
    position: Long,
    duration: Long,
    segments: List<AdSegment>,
    chapters: List<Chapter> = emptyList(),
    onSeek: (Long) -> Unit
) {
    if (duration <= 0) return
    var scrubPosition by remember(duration) { mutableStateOf<Long?>(null) }
    val shownPosition = scrubPosition ?: position
    val fraction = (shownPosition.toFloat() / duration).coerceIn(0f, 1f)
    val activeChapter = remember(chapters, shownPosition, duration) { currentChapter(chapters, shownPosition, duration) }
    Column {
        if (activeChapter != null && activeChapter.title.isNotBlank()) {
            Text(
                activeChapter.title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 2.dp).testTag("current-chapter-title")
            )
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(6.dp)) {
                val y = size.height / 2
                drawLine(Color(0xFF484A54), Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                if (fraction > 0) drawLine(PocketCastsRed, Offset(0f, y), Offset(size.width * fraction, y), size.height, StrokeCap.Round)
                segments.forEach { ad ->
                    val start = (ad.startMs.toFloat() / duration).coerceIn(0f, 1f) * size.width
                    val end = (ad.endMs.toFloat() / duration).coerceIn(0f, 1f) * size.width
                    val color = if (ad.confidence >= AUTO_SKIP_CONFIDENCE) PocketCastsAdGold else PocketCastsAdGold.copy(alpha = 0.45f)
                    if (end > start) drawLine(color, Offset(start, y), Offset(end, y), size.height, StrokeCap.Round)
                }
                chapters.forEach { chapter ->
                    if (chapter.startMs in 1 until duration) {
                        val x = (chapter.startMs.toFloat() / duration).coerceIn(0f, 1f) * size.width
                        drawLine(Color.White.copy(alpha = 0.7f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
                    }
                }
            }
            Slider(
                value = shownPosition.toFloat().coerceIn(0f, duration.toFloat()),
                onValueChange = { scrubPosition = it.toLong() },
                onValueChangeFinished = {
                    scrubPosition?.let(onSeek)
                    scrubPosition = null
                },
                valueRange = 0f..duration.toFloat(),
                colors = SliderDefaults.colors(thumbColor = PocketCastsRed, activeTrackColor = Color.Transparent, inactiveTrackColor = Color.Transparent),
                modifier = Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = "Playback position" }
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(formatTime(shownPosition), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            formatAdSummary(segments)?.let { summary ->
                Text(summary, style = MaterialTheme.typography.labelSmall, color = PocketCastsAdGold)
            }
            Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun MiniPlaybackProgress(position: Long, duration: Long, segments: List<AdSegment>) {
    if (duration <= 0) return
    val fraction = (position.toFloat() / duration).coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp).clip(RoundedCornerShape(50))) {
        val y = size.height / 2
        drawLine(Color(0xFF484A54), Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
        if (fraction > 0) drawLine(PocketCastsRed, Offset(0f, y), Offset(size.width * fraction, y), size.height, StrokeCap.Round)
        segments.forEach { ad ->
            val start = (ad.startMs.toFloat() / duration).coerceIn(0f, 1f) * size.width
            val end = (ad.endMs.toFloat() / duration).coerceIn(0f, 1f) * size.width
            val color = if (ad.confidence >= AUTO_SKIP_CONFIDENCE) PocketCastsAdGold else PocketCastsAdGold.copy(alpha = 0.45f)
            if (end > start) drawLine(color, Offset(start, y), Offset(end, y), size.height, StrokeCap.Round)
        }
    }
}

private val artworkClient = OkHttpClient()
private val artworkCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}

private suspend fun loadArtwork(url: String, pixels: Int): Bitmap? {
    if (!httpUrl(url)) return null
    val key = "$url@$pixels"
    artworkCache.get(key)?.let { return it }
    val request = runCatching { Request.Builder().url(url).build() }.getOrNull() ?: return null
    return suspendCancellableCoroutine { continuation ->
        val call = artworkClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val bitmap = try {
                    response.use {
                        val source = it.body?.source()
                        if (!it.isSuccessful || source == null || source.request(5 * 1024 * 1024L + 1)) null
                        else {
                            val bytes = source.readByteArray()
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                            if (options.outWidth <= 0 || options.outHeight <= 0) null else {
                                var sample = 1
                                while (maxOf(options.outWidth, options.outHeight) / sample > pixels * 2 && sample < (1 shl 29)) sample *= 2
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                            }
                        }
                    }
                } catch (_: Exception) { null }
                if (bitmap != null) artworkCache.put(key, bitmap)
                if (continuation.isActive) continuation.resume(bitmap)
            }
        })
    }
}

@Composable
internal fun PodcastArtwork(title: String, url: String?, size: Dp = 44.dp) {
    val pixels = (size.value * LocalContext.current.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    val bitmap by produceState<Bitmap?>(initialValue = null, url, pixels) {
        value = null
        value = url?.let { loadArtwork(it, pixels) }
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier.size(size).background(Brush.linearGradient(listOf(PocketCastsRed, Color(0xFF8B0000))), shape),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap == null) {
            Text(title.take(2).uppercase(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
        } else {
            Image(bitmap!!.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape).testTag("podcast-artwork-image"))
        }
    }
}

@Composable
private fun TranscriptDialog(
    episodeTitle: String,
    lines: List<TranscriptLine>,
    currentPositionMs: Long,
    onSeek: (Long) -> Unit,
    onMarkAd: (TranscriptLine) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(lines, query) { filterTranscriptLines(lines, query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Transcript • $episodeTitle", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search transcript") },
                    singleLine = true,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).testTag("transcript-search")
                )
                if (filtered.isEmpty()) {
                    Text("No matching transcript lines.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(filtered, key = { it.startMs }) { line ->
                            val active = currentPositionMs in line.startMs..line.endMs
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (active) PocketCastsRed.copy(alpha = 0.18f) else Color.Transparent)
                                    .clickable { onSeek(line.startMs) }
                                    .padding(vertical = 6.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    formatTime(line.startMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (active) PocketCastsRed else PocketCastsAdGold,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(48.dp)
                                )
                                Text(
                                    line.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (active) Color.White else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { onMarkAd(line) }) {
                                    Text("Flag ad", style = MaterialTheme.typography.labelSmall, color = PocketCastsAdGold)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

@Composable
private fun LibraryScreen() {
    val context = LocalContext.current
    val dao = remember { LibraryDatabase.get(context).library() }
    val scope = rememberCoroutineScope()
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
    val selected = podcasts.firstOrNull { it.id == selectedId }
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
    val prefs = remember { context.getSharedPreferences("playback", android.content.Context.MODE_PRIVATE) }
    val effectsPrefs = remember { context.getSharedPreferences("audio_effects", android.content.Context.MODE_PRIVATE) }
    val statsPrefs = remember { context.getSharedPreferences("listening_stats", android.content.Context.MODE_PRIVATE) }
    var trimSilence by remember { mutableStateOf(effectsPrefs.getBoolean("trimSilence", false)) }
    var voiceBoost by remember { mutableStateOf(effectsPrefs.getBoolean("voiceBoost", false)) }
    var autoDeletePlayed by remember { mutableStateOf(effectsPrefs.getBoolean("autoDeletePlayed", false)) }
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

    fun mediaItem(episode: Episode, podcast: Podcast): MediaItem {
        val local = episode.localPath?.let(::File)?.takeIf { it.isFile }
        return QueueItem(episode.id, local?.toURI()?.toString() ?: episode.audioUrl, episode.title, podcast.title, episode.publishedAt).toMediaItem()
    }

    fun prepareForPlayback(episode: Episode) {
        if (episode.status !in listOf("DOWNLOADING", "QUEUED", "PROCESSING", "READY")) scope.launch { enqueueEpisode(context, dao, episode) }
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

    BackHandler(enabled = selectedId != null || tab != "Library") {
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

    transcriptDialogEpisode?.let { (epId, lines) ->
        val epTitle = allEpisodes.firstOrNull { it.id == epId }?.title ?: "Episode"
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
        AlertDialog(
            onDismissRequest = { showStatsDialog = false },
            title = { Text("Listening Stats & Time Saved") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Total listening time: ${formatCompactDuration(stats.totalListenedMs).ifEmpty { "0s" }}", fontWeight = FontWeight.SemiBold)
                    Text("Total time saved: ${formatCompactDuration(stats.totalSavedMs).ifEmpty { "0s" }}", color = PocketCastsAdGold, fontWeight = FontWeight.Bold)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Text("Ads & intros skipped: ${formatCompactDuration(stats.adSkippedMs).ifEmpty { "0s" }}", style = MaterialTheme.typography.bodySmall)
                    Text("Variable speed saved: ${formatCompactDuration(stats.speedSavedMs).ifEmpty { "0s" }}", style = MaterialTheme.typography.bodySmall)
                    Text("Trim silence saved: ${formatCompactDuration(stats.silenceSavedMs).ifEmpty { "0s" }}", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                TextButton(onClick = { showStatsDialog = false }) { Text("Done") }
            }
        )
    }

    if (showSettingsDialog) {
        val storageList = remember(showSettingsDialog, podcasts, allEpisodes) {
            calculatePodcastStorage(context.filesDir, podcasts, allEpisodes)
        }
        AlertDialog(
            onDismissRequest = { showSettingsDialog = false },
            title = { Text("Audio Effects & Storage") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Trim silence globally", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = trimSilence,
                            onCheckedChange = {
                                trimSilence = it
                                effectsPrefs.edit().putBoolean("trimSilence", it).apply()
                            }
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Voice boost (volume leveling)", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = voiceBoost,
                            onCheckedChange = {
                                voiceBoost = it
                                effectsPrefs.edit().putBoolean("voiceBoost", it).apply()
                            }
                        )
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Auto-delete played downloads", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = autoDeletePlayed,
                            onCheckedChange = {
                                autoDeletePlayed = it
                                effectsPrefs.edit().putBoolean("autoDeletePlayed", it).apply()
                            }
                        )
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Text("Downloaded storage", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    if (storageList.isEmpty()) {
                        Text("No downloaded episodes on disk.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        storageList.forEach { usage ->
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(usage.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${usage.episodeCount} ep • ${formatMegabytes(usage.bytes)} MB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = {
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
                                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettingsDialog = false }) { Text("Done") }
            }
        )
    }

    podcastPendingRemoval?.let { targetPodcast ->
        AlertDialog(
            onDismissRequest = { podcastPendingRemoval = null },
            title = { Text("Remove ${targetPodcast.title}?") },
            text = { Text("This removes the subscription and deletes any downloaded episodes for this podcast.") },
            confirmButton = {
                TextButton(
                    modifier = Modifier.testTag("confirm-remove-podcast"),
                    onClick = {
                        podcastPendingRemoval = null
                        scope.launch {
                            val podEpisodes = dao.episodesForPodcast(targetPodcast.id)
                            if (!removePastItemsIfSafe(podEpisodes.map { it.id }.toSet())) {
                                message = "Remove episodes for this podcast from Up Next before removing it."
                            } else {
                                podEpisodes.forEach { File(context.filesDir, "episodes/${it.id}").deleteRecursively() }
                                dao.deletePodcast(targetPodcast.id)
                            }
                        }
                    }
                ) { Text("Remove", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { podcastPendingRemoval = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        bottomBar = {
            Column {
            if (currentId != null && controller != null) {
                Surface(
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    val artistName = controller?.currentMediaItem?.mediaMetadata?.artist?.toString().orEmpty()
                    val playingPodcast = podcasts.firstOrNull { it.title == artistName }
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            PodcastArtwork(artistName.ifBlank { controller?.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty() }, playingPodcast?.artworkUrl, size = 36.dp)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f).clickable { playerExpanded = !playerExpanded }) {
                                Text(controller?.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                                Text(artistName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = {
                                controller?.let { player ->
                                    if (player.isPlaying) player.pause() else {
                                        if (player.playbackState == Player.STATE_IDLE) player.prepare()
                                        player.play()
                                    }
                                }
                            }) {
                                Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = if (playing) "Pause playback" else "Resume playback")
                            }
                            IconButton(onClick = { playerExpanded = !playerExpanded }) {
                                Icon(if (playerExpanded) Icons.Filled.ExpandMore else Icons.Filled.ExpandLess, contentDescription = if (playerExpanded) "Collapse player" else "Expand player")
                            }
                        }
                        if (!playerExpanded && duration > 0) {
                            MiniPlaybackProgress(position, duration, segments)
                        }
                        if (playerExpanded) {
                            PlaybackProgress(position, duration, segments, chapters) { target ->
                                controller?.seekTo(target)
                                position = target
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(
                                        modifier = Modifier.size(28.dp).testTag("speed-down"),
                                        onClick = {
                                            speed = stepSpeed(speed, -0.1f)
                                            controller?.setPlaybackSpeed(speed)
                                        }
                                    ) { Icon(Icons.Filled.Remove, contentDescription = "Decrease speed", modifier = Modifier.size(16.dp)) }
                                    TextButton(onClick = {
                                        speed = when (speed) {
                                            1f -> 1.25f
                                            1.25f -> 1.5f
                                            1.5f -> 2f
                                            else -> 1f
                                        }
                                        controller?.setPlaybackSpeed(speed)
                                    }) { Text("${speed}x", fontWeight = FontWeight.Bold, color = PocketCastsRed) }
                                    IconButton(
                                        modifier = Modifier.size(28.dp).testTag("speed-up"),
                                        onClick = {
                                            speed = stepSpeed(speed, 0.1f)
                                            controller?.setPlaybackSpeed(speed)
                                        }
                                    ) { Icon(Icons.Filled.Add, contentDescription = "Increase speed", modifier = Modifier.size(16.dp)) }
                                }

                                OutlinedButton(
                                    onClick = { controller?.seekTo((position - 15_000).coerceAtLeast(0)) },
                                    shape = RoundedCornerShape(20.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp)
                                ) {
                                    Icon(Icons.Filled.Replay10, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(2.dp))
                                    Text("15s")
                                }

                                Button(
                                    onClick = {
                                        controller?.let { player ->
                                            if (player.isPlaying) player.pause() else {
                                                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                                                player.play()
                                            }
                                        }
                                    },
                                    shape = RoundedCornerShape(24.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PocketCastsRed),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                    modifier = Modifier.height(44.dp)
                                ) {
                                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null)
                                    Spacer(Modifier.width(4.dp))
                                    Text(if (playing) "Pause" else "Play", fontWeight = FontWeight.Bold)
                                }

                                OutlinedButton(
                                    onClick = { controller?.seekTo((position + 30_000).coerceAtMost(duration)) },
                                    shape = RoundedCornerShape(20.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp)
                                ) {
                                    Text("30s")
                                    Spacer(Modifier.width(2.dp))
                                    Icon(Icons.Filled.Forward30, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = trimSilence,
                                    onClick = {
                                        trimSilence = !trimSilence
                                        effectsPrefs.edit().putBoolean("trimSilence", trimSilence).apply()
                                    },
                                    label = { Text("Trim silence", style = MaterialTheme.typography.labelSmall) }
                                )
                                FilterChip(
                                    selected = voiceBoost,
                                    onClick = {
                                        voiceBoost = !voiceBoost
                                        effectsPrefs.edit().putBoolean("voiceBoost", voiceBoost).apply()
                                    },
                                    label = { Text("Voice boost", style = MaterialTheme.typography.labelSmall) }
                                )
                                FilterChip(
                                    selected = sleepLabel != "Off",
                                    onClick = {
                                        val now = System.currentTimeMillis()
                                        val until = prefs.getLong("sleepUntilEpochMs", 0L)
                                        val eoe = prefs.getBoolean("sleepEndOfEpisode", false)
                                        val remainingMin = if (until > now) (until - now) / 60_000L else 0L
                                        when {
                                            !eoe && remainingMin <= 0L -> {
                                                prefs.edit().putLong("sleepUntilEpochMs", now + 15 * 60_000L).putBoolean("sleepEndOfEpisode", false).apply()
                                                sleepLabel = "15m"
                                            }
                                            remainingMin in 1..15 -> {
                                                prefs.edit().putLong("sleepUntilEpochMs", now + 30 * 60_000L).putBoolean("sleepEndOfEpisode", false).apply()
                                                sleepLabel = "30m"
                                            }
                                            remainingMin in 16..30 -> {
                                                prefs.edit().putLong("sleepUntilEpochMs", now + 60 * 60_000L).putBoolean("sleepEndOfEpisode", false).apply()
                                                sleepLabel = "60m"
                                            }
                                            remainingMin > 30 -> {
                                                prefs.edit().putLong("sleepUntilEpochMs", 0L).putBoolean("sleepEndOfEpisode", true).apply()
                                                sleepLabel = "End of ep"
                                            }
                                            else -> {
                                                prefs.edit().putLong("sleepUntilEpochMs", 0L).putBoolean("sleepEndOfEpisode", false).apply()
                                                sleepLabel = "Off"
                                            }
                                        }
                                    },
                                    label = { Text("Sleep: $sleepLabel", style = MaterialTheme.typography.labelSmall) }
                                )
                                if (chapters.isNotEmpty()) {
                                    FilterChip(
                                        selected = showPlayerChapters,
                                        onClick = { showPlayerChapters = !showPlayerChapters },
                                        label = { Text("Chapters (${chapters.size})", style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                                currentId?.let { playingEpId ->
                                    val playingEp = allEpisodes.firstOrNull { it.id == playingEpId }
                                    if (playingEp != null && File(context.filesDir, "episodes/$playingEpId/transcript.tsv").isFile) {
                                        FilterChip(
                                            selected = false,
                                            onClick = { openTranscriptFor(playingEp) },
                                            label = { Text("Transcript", style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                            }
                            if (showPlayerChapters && chapters.isNotEmpty()) {
                                Column(Modifier.fillMaxWidth().heightIn(max = 160.dp).padding(top = 4.dp)) {
                                    LazyColumn {
                                        items(chapters.size) { idx ->
                                            val ch = chapters[idx]
                                            val chEnd = ch.endMs ?: chapters.getOrNull(idx + 1)?.startMs ?: duration
                                            val skippedSeg = segments.firstOrNull { it.startMs == ch.startMs && it.source == "chapter" }
                                            Row(
                                                Modifier.fillMaxWidth().clickable {
                                                    controller?.seekTo(ch.startMs)
                                                    position = ch.startMs
                                                }.padding(vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(formatTime(ch.startMs), style = MaterialTheme.typography.labelSmall, color = PocketCastsRed, modifier = Modifier.width(48.dp))
                                                Text(ch.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                TextButton(onClick = {
                                                    val id = currentId ?: return@TextButton
                                                    scope.launch {
                                                        if (skippedSeg != null) {
                                                            dao.deleteSegment(skippedSeg.id)
                                                        } else if (chEnd > ch.startMs) {
                                                            dao.saveSegments(listOf(AdSegment(episodeId = id, startMs = ch.startMs, endMs = chEnd, source = "chapter", confidence = 0.9f)))
                                                        }
                                                    }
                                                }) {
                                                    Text(if (skippedSeg != null) "Unskip" else "Skip", style = MaterialTheme.typography.labelSmall, color = PocketCastsAdGold)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        val currentAd = segments.firstOrNull { position >= it.startMs && position < it.endMs }
                        if (currentAd != null || lastSkip == currentId) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (currentAd != null) {
                                    TextButton(onClick = {
                                        ListeningStatsStore.recordAdSkipped(statsPrefs, (currentAd.endMs - position).coerceAtLeast(0L))
                                        controller?.seekTo(currentAd.endMs)
                                    }) {
                                        Text("Skip ad", color = PocketCastsAdGold, fontWeight = FontWeight.Bold)
                                    }
                                }
                                if (lastSkip == currentId) {
                                    TextButton(
                                        onClick = {
                                            val id = currentId
                                            scope.launch {
                                                val start = prefs.getLong("skipStart", 0)
                                                if (id != null) dao.segments(id).filter { it.startMs == start }.forEach { dao.deleteSegment(it.id) }
                                                controller?.seekTo(start + 1)
                                                prefs.edit().remove("episode").apply()
                                                lastSkip = null
                                            }
                                        }
                                    ) { Text("Restore last skipped section", color = PocketCastsRed) }
                                }
                            }
                        }
                    }
                }
            }
        NavigationBar {
            listOf(
                Triple("Library", Icons.Filled.LibraryMusic, "Library"),
                Triple("Filters", Icons.Filled.FilterList, "Filters"),
                Triple("Discover", Icons.Filled.Search, "Discover"),
                Triple("Up Next", Icons.AutoMirrored.Filled.QueueMusic, "Up Next")
            ).forEach { (label, icon, destination) ->
                NavigationBarItem(selected = tab == destination, onClick = { tab = destination; selectedId = null }, icon = { Icon(icon, contentDescription = null) }, label = { Text(label) })
            }
        }
        }
    }
) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (tab == "Up Next") {
                Text("Up Next", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text("Your listening queue", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp, bottom = 16.dp))
                val upcoming = queue.drop(currentIndex)
                if (upcoming.isEmpty()) {
                    Text("Your queue is empty", style = MaterialTheme.typography.titleMedium)
                    Text("Add episodes from your library to listen to them in order.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(upcoming.size) { index ->
                            val item = upcoming[index]
                            val title = item.mediaMetadata.title?.toString().orEmpty()
                            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("queue-$title"), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(12.dp)) {
                                Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f).clickable {
                                        controller?.let { player ->
                                            val target = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == item.mediaId }
                                            if (target != null) {
                                                if (target != player.currentMediaItemIndex || player.playbackState == Player.STATE_ENDED) player.seekTo(target, 0)
                                                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                                                player.play()
                                            }
                                        }
                                    }) {
                                        if (item.mediaId == currentId) Text("CURRENT EPISODE", style = MaterialTheme.typography.labelSmall, color = PocketCastsRed)
                                        Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        val published = item.toQueueItem().publishedAt?.let(::formatPublished)
                                        Text(listOfNotNull(item.mediaMetadata.artist?.toString()?.takeIf { it.isNotBlank() }, published).joinToString(" \u2022 "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                    IconButton(enabled = index > 1, onClick = { controller?.moveMediaItem(currentIndex + index, currentIndex + index - 1) }) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move $title up") }
                                    IconButton(enabled = index > 0 && index < upcoming.lastIndex, onClick = { controller?.moveMediaItem(currentIndex + index, currentIndex + index + 1) }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move $title down") }
                                    IconButton(onClick = { controller?.removeMediaItem(currentIndex + index) }) { Icon(Icons.Filled.Close, contentDescription = "Remove $title from Up Next") }
                                }
                            }
                        }
                    }
                }
            } else if (tab == "Filters") {
                Text("Filters", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf("New Releases", "In Progress", "Downloaded", "Starred").forEach { name ->
                        FilterChip(
                            selected = smartFilter == name,
                            onClick = { smartFilter = name },
                            label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.testTag("smart-filter-$name")
                        )
                    }
                }
                OutlinedTextField(
                    value = smartQuery,
                    onValueChange = { smartQuery = it },
                    label = { Text("Filter episodes across all shows") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                )
                val filteredSmart = remember(allEpisodes, smartFilter, smartQuery) {
                    filterSmartEpisodes(allEpisodes, smartFilter, smartQuery)
                }
                if (filteredSmart.isEmpty()) {
                    Text("No episodes in $smartFilter.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(filteredSmart, key = { it.id }) { ep ->
                            val pod = podcastsById[ep.podcastId]
                            Card(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                    PodcastArtwork(pod?.title ?: ep.title, pod?.artworkUrl, size = 40.dp)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(ep.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        val meta = listOfNotNull(pod?.title, ep.publishedAt?.let(::formatPublished), progressLabel(ep.positionMs, ep.durationMs, ep.finished))
                                        Text(meta.joinToString(" • "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                    IconButton(onClick = { scope.launch { dao.setStarred(ep.id, !ep.starred) } }) {
                                        Icon(if (ep.starred) Icons.Filled.Star else Icons.Filled.StarBorder, contentDescription = "Star episode", tint = if (ep.starred) PocketCastsAdGold else MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    IconButton(onClick = {
                                        if (pod != null) {
                                            controller?.let { player ->
                                                val existing = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == ep.id }
                                                val index = existing ?: player.mediaItemCount.also { player.addMediaItem(mediaItem(ep, pod)) }
                                                if (index != player.currentMediaItemIndex || player.playbackState == Player.STATE_ENDED) player.seekTo(index, 0)
                                                player.prepare()
                                                player.play()
                                                prepareForPlayback(ep)
                                            }
                                        }
                                    }) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = "Play ${ep.title}", tint = PocketCastsRed)
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (tab == "Discover") {
                Text("Discover", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search podcasts") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runPodcastSearch() }),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PocketCastsRed,
                        focusedLabelColor = PocketCastsRed
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Button(
                    enabled = !searching && searchQuery.isNotBlank(),
                    onClick = { runPodcastSearch() },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PocketCastsRed),
                    modifier = Modifier.padding(top = 4.dp)
                ) { Text(if (searching) "Searching..." else "Search") }

                if (searchResults.isNotEmpty()) {
                    Text("Search Results", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        items(searchResults, key = { it.feedUrl }) { item ->
                            val subscribed = isPodcastSubscribed(podcasts, item.feedUrl)
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                PodcastArtwork(item.title, item.artworkUrl)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                    if (item.artist.isNotEmpty()) Text(item.artist, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (subscribed) {
                                    OutlinedButton(
                                        onClick = {
                                            val hash = stableHash(item.feedUrl.trim())
                                            podcasts.firstOrNull { it.id == hash || it.feedUrl.equals(item.feedUrl.trim(), ignoreCase = true) }?.let {
                                                selectedId = it.id
                                                tab = "Library"
                                            }
                                        }
                                    ) { Text("Subscribed") }
                                } else {
                                    Button(
                                        enabled = !loading,
                                        onClick = { scope.launch { subscribeUrl(item.feedUrl) } },
                                        colors = ButtonDefaults.buttonColors(containerColor = PocketCastsRed)
                                    ) {
                                        Text("Subscribe")
                                    }
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = feedUrl,
                    onValueChange = { feedUrl = it },
                    label = { Text("Feed URL") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { if (!loading && feedUrl.isNotBlank()) scope.launch { subscribeUrl(feedUrl) } }),
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PocketCastsRed,
                        focusedLabelColor = PocketCastsRed
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Button(
                    enabled = !loading && feedUrl.isNotBlank(),
                    onClick = { scope.launch { subscribeUrl(feedUrl) } },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PocketCastsRed),
                    modifier = Modifier.padding(top = 4.dp)
                ) { Text(if (loading) "Loading..." else "Subscribe") }

            } else if (selected == null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
                    if (!version.isNullOrBlank()) {
                        Text("v$version", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { gridLayout = !gridLayout }, modifier = Modifier.testTag("toggle-library-layout")) {
                        Icon(if (gridLayout) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView, contentDescription = "Toggle grid layout")
                    }
                    IconButton(onClick = { showStatsDialog = true }, modifier = Modifier.testTag("open-stats")) {
                        Icon(Icons.Filled.Insights, contentDescription = "Listening stats")
                    }
                    IconButton(onClick = { showSettingsDialog = true }, modifier = Modifier.testTag("open-settings")) {
                        Icon(Icons.Filled.Tune, contentDescription = "Audio and storage settings")
                    }
                }
                update?.let { release ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag("update-banner")
                    ) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Unsplice v${release.version} is available", fontWeight = FontWeight.Bold)
                                Text("Open the download to update. Your podcasts and progress stay.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release.apkUrl))) }) { Text("Download") }
                        }
                    }
                }
                Text("Your podcasts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                if (podcasts.isEmpty()) Text("Find a podcast in Discover to get started.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (gridLayout) {
                    val rows = remember(podcasts) { podcasts.chunked(3) }
                    LazyColumn(Modifier.weight(1f)) {
                        items(rows.size) { rowIndex ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                for (podcast in rows[rowIndex]) {
                                    val unplayed = unplayedCounts[podcast.id] ?: 0
                                    Column(
                                        Modifier.weight(1f).clickable { selectedId = podcast.id },
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box {
                                            PodcastArtwork(podcast.title, podcast.artworkUrl, size = 92.dp)
                                            if (unplayed > 0) {
                                                Box(
                                                    Modifier
                                                        .align(Alignment.TopEnd)
                                                        .padding(4.dp)
                                                        .background(PocketCastsRed, CircleShape)
                                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                                ) {
                                                    Text("$unplayed", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
                                                }
                                            }
                                        }
                                        Text(podcast.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                                    }
                                }
                                repeat(3 - rows[rowIndex].size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                } else {
                    LazyColumn(Modifier.weight(1f)) {
                        items(podcasts, key = { it.id }) { podcast ->
                            val unplayed = unplayedCounts[podcast.id] ?: 0
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                                    .clickable { selectedId = podcast.id },
                                shape = RoundedCornerShape(12.dp),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    PodcastArtwork(podcast.title, podcast.artworkUrl)
                                    Spacer(Modifier.width(12.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(podcast.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                        val statusLabel = buildString {
                                            append(if (podcast.autoSkip) "Auto-skip active" else "Auto-skip off")
                                            if (unplayed > 0) append(" • $unplayed unplayed")
                                        }
                                        Text(
                                            statusLabel,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (podcast.autoSkip) PocketCastsAdGold else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    TextButton(onClick = { podcastPendingRemoval = podcast }) {
                                        Text("Remove", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { selectedId = null }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp), tint = PocketCastsRed)
                        Spacer(Modifier.width(4.dp))
                        Text("All podcasts", color = PocketCastsRed)
                    }
                }
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            PodcastArtwork(selected.title, selected.artworkUrl)
                            Spacer(Modifier.width(12.dp))
                            Text(selected.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(
                                onClick = { podcastSettingsOpen = !podcastSettingsOpen },
                                modifier = Modifier.testTag("toggle-podcast-settings")
                            ) { Icon(Icons.Filled.Settings, contentDescription = "Podcast settings") }
                            IconButton(enabled = !refreshing, onClick = {
                                scope.launch {
                                    refreshing = true
                                    try { DefaultSubscription.subscribeFeed(selected.feedUrl, dao) } catch (e: Exception) { message = e.message ?: "Could not refresh the feed" } finally { refreshing = false }
                                }
                            }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh episodes") }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Automatically skip detected ads", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                Text("Detected ad breaks are skipped automatically during playback.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(
                                checked = selected.autoSkip,
                                onCheckedChange = { enabled -> scope.launch { dao.savePodcast(selected.copy(autoSkip = enabled)) } },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = PocketCastsRed
                                )
                            )
                        }
                        if (podcastSettingsOpen) {
                            HorizontalDivider(Modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.outline)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Skip intro", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = {
                                    val next = when (selected.skipIntroSec) { 0 -> 15; 15 -> 30; 30 -> 45; 45 -> 60; else -> 0 }
                                    scope.launch { dao.savePodcast(selected.copy(skipIntroSec = next)) }
                                }) { Text("${selected.skipIntroSec}s", color = PocketCastsRed, fontWeight = FontWeight.Bold) }
                                Text("Skip outro", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = {
                                    val next = when (selected.skipOutroSec) { 0 -> 15; 15 -> 30; 30 -> 45; 45 -> 60; else -> 0 }
                                    scope.launch { dao.savePodcast(selected.copy(skipOutroSec = next)) }
                                }) { Text("${selected.skipOutroSec}s", color = PocketCastsRed, fontWeight = FontWeight.Bold) }
                                Text("Speed", style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = {
                                    val next = when (selected.customSpeed) { null -> 1.2f; 1.2f -> 1.5f; 1.5f -> 2.0f; else -> null }
                                    scope.launch { dao.savePodcast(selected.copy(customSpeed = next)) }
                                }) { Text(selected.customSpeed?.let { "${it}x" } ?: "Default", color = PocketCastsRed, fontWeight = FontWeight.Bold) }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("Auto-download new episodes", style = MaterialTheme.typography.bodySmall)
                                Switch(
                                    checked = selected.autoDownload,
                                    onCheckedChange = { enabled -> scope.launch { dao.savePodcast(selected.copy(autoDownload = enabled)) } }
                                )
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("All", "Unplayed", "Downloaded").forEach { filterOption ->
                        FilterChip(
                            selected = episodeFilter == filterOption,
                            onClick = { episodeFilter = filterOption },
                            label = { Text(filterOption, style = MaterialTheme.typography.labelSmall) },
                            modifier = Modifier.padding(end = 6.dp).testTag("filter-$filterOption")
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        episodeSearchOpen = !episodeSearchOpen
                        if (!episodeSearchOpen) episodeQuery = ""
                    }) {
                        Icon(if (episodeSearchOpen) Icons.Filled.Close else Icons.Filled.Search, contentDescription = "Search episodes")
                    }
                    TextButton(onClick = { oldestFirst = !oldestFirst }, modifier = Modifier.testTag("sort-episodes")) {
                        Icon(Icons.Filled.SwapVert, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (oldestFirst) "Oldest first" else "Newest first", style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (episodeSearchOpen) {
                    OutlinedTextField(
                        value = episodeQuery,
                        onValueChange = { episodeQuery = it },
                        label = { Text("Search episodes") },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PocketCastsRed,
                            focusedLabelColor = PocketCastsRed
                        ),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp).testTag("episode-search")
                    )
                }
                val visibleEpisodes = remember(episodes, episodeQuery, episodeFilter, oldestFirst) {
                    filterEpisodes(episodes, episodeQuery, episodeFilter, oldestFirst)
                }
                LazyColumn(Modifier.weight(1f).testTag("episodes")) {
                    items(visibleEpisodes, key = { it.id }) { episode ->
                        val epSegments = segmentsByEpisode[episode.id].orEmpty()
                        val activeProgress = activeProgressMap[episode.id]
                        var dragAccumulated by remember(episode.id) { mutableFloatStateOf(0f) }
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .pointerInput(episode.id, episode.finished) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { dragAccumulated = 0f },
                                        onDragEnd = {
                                            if (dragAccumulated > 160f) {
                                                controller?.let { player ->
                                                    val existing = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == episode.id }
                                                    if (existing == null || existing < player.currentMediaItemIndex) {
                                                        if (existing != null) player.removeMediaItem(existing)
                                                        player.addMediaItem(mediaItem(episode, selected))
                                                        prepareForPlayback(episode)
                                                        message = "Added to Up Next"
                                                    }
                                                }
                                            } else if (dragAccumulated < -160f) {
                                                scope.launch { dao.setFinished(episode.id, !episode.finished) }
                                            }
                                            dragAccumulated = 0f
                                        },
                                        onHorizontalDrag = { _, dragAmount -> dragAccumulated += dragAmount }
                                    )
                                },
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Text(episode.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    IconButton(
                                        modifier = Modifier.size(28.dp).testTag("star-${episode.id}"),
                                        onClick = { scope.launch { dao.setStarred(episode.id, !episode.starred) } }
                                    ) {
                                        Icon(
                                            if (episode.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                                            contentDescription = if (episode.starred) "Unstar episode" else "Star episode",
                                            tint = if (episode.starred) PocketCastsAdGold else MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                val details = listOfNotNull(episode.publishedAt?.let(::formatPublished), progressLabel(episode.positionMs, episode.durationMs, episode.finished))
                                if (details.isNotEmpty()) {
                                    Text(details.joinToString(" \u2022 "), style = MaterialTheme.typography.bodySmall,
                                        color = if (episode.finished) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.testTag("episode-details-${episode.id}"))
                                }
                                if (!episode.description.isNullOrBlank()) {
                                    val notesExpanded = episode.id in expandedNotesEpisodes
                                    Text(
                                        if (notesExpanded) "Hide show notes" else "Show notes",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier
                                            .clickable {
                                                expandedNotesEpisodes = if (notesExpanded) expandedNotesEpisodes - episode.id else expandedNotesEpisodes + episode.id
                                            }
                                            .padding(vertical = 2.dp)
                                            .testTag("notes-${episode.id}")
                                    )
                                    if (notesExpanded) {
                                        Text(episode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp))
                                        val timestamps = remember(episode.description) { extractTimestamps(episode.description) }
                                        if (timestamps.isNotEmpty()) {
                                            Row(
                                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                timestamps.forEach { ts ->
                                                    AssistChip(
                                                        onClick = {
                                                            controller?.let { player ->
                                                                val existing = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == episode.id }
                                                                val index = existing ?: player.mediaItemCount.also { player.addMediaItem(mediaItem(episode, selected)) }
                                                                player.seekTo(index, ts.positionMs)
                                                                player.prepare()
                                                                player.play()
                                                            }
                                                        },
                                                        label = { Text(ts.label, style = MaterialTheme.typography.labelSmall) }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                val statusText = activeProgress?.summary() ?: when (episode.status) {
                                    "PROCESSING" -> "Finding ads…"
                                    "DOWNLOADING" -> "Downloading episode"
                                    "QUEUED" -> "Queued"
                                    else -> null
                                }
                                if (statusText != null) {
                                    Text(statusText, style = MaterialTheme.typography.bodySmall, color = PocketCastsRed, modifier = Modifier.testTag("episode-progress-${episode.id}"))
                                    if (activeProgress?.percent != null) {
                                        LinearProgressIndicator(
                                            progress = { (activeProgress.percent / 100f).coerceIn(0f, 1f) },
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp).height(4.dp).clip(RoundedCornerShape(50)),
                                            color = PocketCastsRed,
                                            trackColor = Color(0xFF484A54)
                                        )
                                    } else {
                                        LinearProgressIndicator(
                                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp).height(4.dp).clip(RoundedCornerShape(50)),
                                            color = PocketCastsRed,
                                            trackColor = Color(0xFF484A54)
                                        )
                                    }
                                }
                                if (episode.status == "READY") {
                                    if (epSegments.isNotEmpty()) {
                                        val segmentsExpanded = episode.id in expandedSegmentEpisodes
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier
                                                .clickable {
                                                    expandedSegmentEpisodes = if (segmentsExpanded) expandedSegmentEpisodes - episode.id else expandedSegmentEpisodes + episode.id
                                                }
                                                .padding(vertical = 2.dp)
                                                .testTag("toggle-segments-${episode.id}")
                                        ) {
                                            Text(
                                                formatAdSummary(epSegments, selected.autoSkip).orEmpty(),
                                                color = PocketCastsAdGold,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Icon(
                                                if (segmentsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                                contentDescription = if (segmentsExpanded) "Hide ad segments" else "Show ad segments",
                                                tint = PocketCastsAdGold,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                        if (segmentsExpanded) {
                                            epSegments.forEach { seg ->
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    val label = when (seg.source) { "marker" -> "ad break"; "chapter" -> "chapter"; "transcript" -> "sponsor read"; else -> "repeat" }
                                                    val manual = if (seg.confidence < AUTO_SKIP_CONFIDENCE) ", not auto-skipped" else ""
                                                    Text("${formatTime(seg.startMs)} - ${formatTime(seg.endMs)} ($label$manual)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                    Spacer(Modifier.width(8.dp))
                                                    TextButton(onClick = { scope.launch { dao.deleteSegment(seg.id) } }) {
                                                        Text("Keep audio", style = MaterialTheme.typography.bodySmall)
                                                    }
                                                }
                                            }
                                        }
                                    } else if (activeProgress == null) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("No ads found", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(Modifier.width(8.dp))
                                            TextButton(onClick = { startDownload(episode) }) {
                                                Text("Scan again", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                    if (File(context.filesDir, "episodes/${episode.id}/transcript.tsv").isFile) {
                                        TextButton(
                                            onClick = { openTranscriptFor(episode) },
                                            contentPadding = PaddingValues(0.dp)
                                        ) { Text("View transcript", style = MaterialTheme.typography.labelSmall, color = PocketCastsRed) }
                                    }
                                }
                                episode.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Button(
                                        onClick = {
                                            controller?.let { player ->
                                                val existing = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == episode.id }
                                                val index = existing ?: player.mediaItemCount.also { player.addMediaItem(mediaItem(episode, selected)) }
                                                if (index != player.currentMediaItemIndex || player.playbackState == Player.STATE_ENDED) player.seekTo(index, 0)
                                                player.prepare()
                                                player.play()
                                                prepareForPlayback(episode)
                                            } ?: run { message = "Player is starting. Try again." }
                                        },
                                        shape = RoundedCornerShape(20.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = PocketCastsRed)
                                    ) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Play")
                                    }
                                    TextButton(modifier = Modifier.testTag("add-${episode.title}"), onClick = {
                                        controller?.let { player ->
                                            val existing = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == episode.id }
                                            if (existing == null || existing < player.currentMediaItemIndex) {
                                                if (existing != null) player.removeMediaItem(existing)
                                                player.addMediaItem(mediaItem(episode, selected))
                                                prepareForPlayback(episode)
                                            } else message = "Already in Up Next"
                                        } ?: run { message = "Player is starting. Try again." }
                                    }) { Text("Add to Up Next") }
                                    Spacer(Modifier.weight(1f))
                                    val isWorking = activeProgress != null || episode.status in listOf("DOWNLOADING", "QUEUED", "PROCESSING")
                                    if (isWorking) {
                                        TextButton(
                                            modifier = Modifier.testTag("cancel-${episode.id}"),
                                            onClick = { scope.launch { cancelEpisode(context, dao, episode) } }
                                        ) { Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    } else {
                                        if (episode.localPath != null && File(episode.localPath).isFile) {
                                            TextButton(onClick = {
                                                scope.launch {
                                                    if (!removePastItemsIfSafe(setOf(episode.id))) {
                                                        message = "Remove this episode from Up Next before deleting its download."
                                                    } else {
                                                        File(context.filesDir, "episodes/${episode.id}").deleteRecursively()
                                                        dao.saveEpisode(episode.copy(status = "NOT_DOWNLOADED", localPath = null, fingerprintPath = null))
                                                        dao.deleteSegments(episode.id)
                                                    }
                                                }
                                            }) { Text("Delete", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                        }
                                        if (episode.status !in listOf("DOWNLOADING", "QUEUED", "PROCESSING", "READY")) {
                                            Button(
                                                onClick = { startDownload(episode) },
                                                shape = RoundedCornerShape(20.dp),
                                                colors = ButtonDefaults.buttonColors(containerColor = PocketCastsSurfaceVariant)
                                            ) {
                                                Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text(if (episode.status == "FAILED") "Retry" else "Download")
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
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
