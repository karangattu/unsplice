package com.echomute

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.work.*
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
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.math.roundToInt

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
internal fun PlaybackProgress(position: Long, duration: Long, segments: List<AdSegment>, onSeek: (Long) -> Unit) {
    if (duration <= 0) return
    var scrubPosition by remember(duration) { mutableStateOf<Long?>(null) }
    val shownPosition = scrubPosition ?: position
    val fraction = (shownPosition.toFloat() / duration).coerceIn(0f, 1f)
    Column {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(6.dp)) {
                val y = size.height / 2
                drawLine(Color(0xFF484A54), Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                if (fraction > 0) drawLine(PocketCastsRed, Offset(0f, y), Offset(size.width * fraction, y), size.height, StrokeCap.Round)
                segments.forEach { ad ->
                    val start = (ad.startMs.toFloat() / duration).coerceIn(0f, 1f) * size.width
                    val end = (ad.endMs.toFloat() / duration).coerceIn(0f, 1f) * size.width
                    if (end > start) drawLine(PocketCastsAdGold, Offset(start, y), Offset(end, y), size.height, StrokeCap.Round)
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime(shownPosition), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
internal fun PodcastArtwork(title: String, url: String?) {
    val pixels = (44 * LocalContext.current.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    val bitmap by produceState<Bitmap?>(initialValue = null, url, pixels) {
        value = null
        value = url?.let { loadArtwork(it, pixels) }
    }
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier = Modifier.size(44.dp).background(Brush.linearGradient(listOf(PocketCastsRed, Color(0xFF8B0000))), shape),
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
    var searchQuery by remember { mutableStateOf("") }
    var searchResults by remember { mutableStateOf(emptyList<PodcastSearchResult>()) }
    var searching by remember { mutableStateOf(false) }
    var feedUrl by remember { mutableStateOf("") }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val selected = podcasts.firstOrNull { it.id == selectedId }
    val episodeFlow = remember(selectedId) { selectedId?.let { dao.observeEpisodes(it) } }
    val episodes = if (episodeFlow != null) episodeFlow.collectAsState(initial = emptyList()).value else emptyList()
    var controller by remember { mutableStateOf<MediaController?>(null) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    var currentId by remember { mutableStateOf<String?>(null) }
    var segments by remember { mutableStateOf(emptyList<AdSegment>()) }
    val prefs = remember { context.getSharedPreferences("playback", android.content.Context.MODE_PRIVATE) }
    var lastSkip by remember { mutableStateOf<String?>(null) }
    var speed by remember { mutableFloatStateOf(1f) }
    var tab by remember { mutableStateOf("Library") }
    var playerExpanded by remember { mutableStateOf(false) }
    var queue by remember { mutableStateOf(emptyList<MediaItem>()) }
    var currentIndex by remember { mutableIntStateOf(0) }

    fun mediaItem(episode: Episode, podcast: Podcast): MediaItem {
        val local = episode.localPath?.let(::File)?.takeIf { it.isFile }
        return MediaItem.Builder()
            .setMediaId(episode.id)
            .setUri(local?.toURI()?.toString() ?: episode.audioUrl)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(episode.title).setArtist(podcast.title).build())
            .build()
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
                    // Android 15 draws apps edge-to-edge, so keep the controls above the system navigation bar.
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { playerExpanded = !playerExpanded }) {
                                Text(controller?.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold)
                                Text(controller?.currentMediaItem?.mediaMetadata?.artist?.toString().orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            LinearProgressIndicator(
                                progress = { (position.toFloat() / duration).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)),
                                color = PocketCastsRed,
                                trackColor = Color(0xFF484A54)
                            )
                        }
                        if (playerExpanded) {
                            PlaybackProgress(position, duration, segments) { target ->
                                controller?.seekTo(target)
                                position = target
                            }
                            Row(
                                Modifier.fillMaxWidth().padding(top = 4.dp),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = {
                                    speed = when (speed) {
                                        1f -> 1.25f
                                        1.25f -> 1.5f
                                        1.5f -> 2f
                                        else -> 1f
                                    }
                                    controller?.setPlaybackSpeed(speed)
                                }) { Text("${speed}x", fontWeight = FontWeight.Bold, color = PocketCastsRed) }

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
                            val currentAd = segments.firstOrNull { position >= it.startMs && position < it.endMs }
                            if (currentAd != null) {
                                TextButton(onClick = { controller?.seekTo(currentAd.endMs) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                                    Text("Skip ad", color = PocketCastsAdGold, fontWeight = FontWeight.Bold)
                                }
                            }
                            if (lastSkip == currentId) {
                                TextButton(
                                    onClick = {
                                        val id = currentId
                                        scope.launch {
                                            val episode = id?.let { dao.episode(it) }
                                            val podcast = episode?.let { dao.podcast(it.podcastId) }
                                            if (podcast != null) dao.savePodcast(podcast.copy(autoSkip = false))
                                            // 1 ms past the start, so the skip message placed exactly at the start doesn't fire again.
                                            controller?.seekTo(prefs.getLong("skipStart", 0) + 1)
                                            prefs.edit().remove("episode").apply()
                                            lastSkip = null
                                        }
                                    },
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                ) { Text("Restore last skipped section", color = PocketCastsRed) }
                    }
                    }
                }
            }
        }
        NavigationBar {
            listOf(Triple("Library", Icons.Filled.LibraryMusic, "Library"), Triple("Discover", Icons.Filled.Search, "Discover"), Triple("Up Next", Icons.AutoMirrored.Filled.QueueMusic, "Up Next")).forEach { (label, icon, destination) ->
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
                                        Text(item.mediaMetadata.artist?.toString().orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                                    }
                                    IconButton(enabled = index > 1, onClick = { controller?.moveMediaItem(currentIndex + index, currentIndex + index - 1) }) { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move $title up") }
                                    IconButton(enabled = index > 0 && index < upcoming.lastIndex, onClick = { controller?.moveMediaItem(currentIndex + index, currentIndex + index + 1) }) { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move $title down") }
                                    IconButton(onClick = { controller?.removeMediaItem(currentIndex + index) }) { Icon(Icons.Filled.Close, contentDescription = "Remove $title from Up Next") }
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
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PocketCastsRed,
                        focusedLabelColor = PocketCastsRed
                    ),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                )
                Button(
                    enabled = !searching && searchQuery.isNotBlank(),
                    onClick = {
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
                    },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PocketCastsRed),
                    modifier = Modifier.padding(top = 4.dp)
                ) { Text(if (searching) "Searching..." else "Search") }

                if (searchResults.isNotEmpty()) {
                    Text("Search Results", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    LazyColumn(Modifier.heightIn(max = 200.dp)) {
                        items(searchResults, key = { it.feedUrl }) { item ->
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

                OutlinedTextField(
                    value = feedUrl,
                    onValueChange = { feedUrl = it },
                    label = { Text("Feed URL") },
                    singleLine = true,
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
                }
                Text("Your podcasts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
                if (podcasts.isEmpty()) Text("Find a podcast in Discover to get started.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                LazyColumn(Modifier.weight(1f)) {
                    items(podcasts, key = { it.id }) { podcast ->
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
                                    Text(
                                        if (podcast.autoSkip) "Auto-skip active" else "Auto-skip off",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (podcast.autoSkip) PocketCastsAdGold else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                TextButton(onClick = {
                                    scope.launch {
                                        val episodes = dao.episodesForPodcast(podcast.id)
                                        if (!removePastItemsIfSafe(episodes.map { it.id }.toSet())) {
                                            message = "Remove episodes for this podcast from Up Next before removing it."
                                        } else {
                                            episodes.forEach { File(context.filesDir, "episodes/${it.id}").deleteRecursively() }
                                            dao.deletePodcast(podcast.id)
                                        }
                                    }
                                }) { Text("Remove", color = MaterialTheme.colorScheme.error) }
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
                            Text(selected.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                    }
                }
                Text("Episodes", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                LazyColumn(Modifier.weight(1f).testTag("episodes")) {
                    items(episodes, key = { it.id }) { episode ->
                        val epSegments by dao.observeSegments(episode.id).collectAsState(initial = emptyList())
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            shape = RoundedCornerShape(12.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Text(episode.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                if (episode.status in listOf("DOWNLOADING", "QUEUED", "PROCESSING")) {
                                    Text(if (episode.status == "PROCESSING") "Finding ads…" else episode.status.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodySmall, color = PocketCastsRed)
                                }
                                if (episode.status == "READY") {
                                    if (epSegments.isNotEmpty()) {
                                        Text("${epSegments.size} ad segment(s) detected", color = PocketCastsAdGold, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                        epSegments.forEach { seg ->
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                val label = when (seg.source) { "marker" -> "ad break"; "chapter" -> "chapter"; else -> "repeat" }
                                                val manual = if (seg.confidence < AUTO_SKIP_CONFIDENCE) ", not auto-skipped" else ""
                                                Text("${formatTime(seg.startMs)} - ${formatTime(seg.endMs)} ($label$manual)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                Spacer(Modifier.width(8.dp))
                                                TextButton(onClick = { scope.launch { dao.deleteSegment(seg.id) } }) {
                                                    Text("Keep audio", style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                        }
                                    } else {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text("No ads found", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            Spacer(Modifier.width(8.dp))
                                            TextButton(onClick = { startDownload(episode) }) {
                                                Text("Scan again", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                                episode.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Button(
                                        onClick = {
                                            controller?.let { player ->
                                                val existing = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == episode.id }
                                                val index = existing ?: player.mediaItemCount.also { player.addMediaItem(mediaItem(episode, selected)) }
                                                if (index != player.currentMediaItemIndex || player.playbackState == Player.STATE_ENDED) player.seekTo(index, 0)
                                                player.prepare()
                                                player.play()
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
                                            } else message = "Already in Up Next"
                                        } ?: run { message = "Player is starting. Try again." }
                                    }) { Text("Add to Up Next") }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically) {
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
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        }
    }
}

/** Downloads the episode if needed, then finds its ads from scratch. */
private suspend fun enqueueEpisode(context: android.content.Context, dao: LibraryDao, episode: Episode) {
    dao.deleteSegments(episode.id)
    dao.saveEpisode(episode.copy(status = "QUEUED", error = null))
    val request = OneTimeWorkRequestBuilder<EpisodeWorker>().setInputData(workDataOf("episodeId" to episode.id)).build()
    WorkManager.getInstance(context).enqueueUniqueWork("episode-${episode.id}", ExistingWorkPolicy.KEEP, request)
}
