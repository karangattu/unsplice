package com.echomute

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.withContext
import java.io.File

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
    return "%02d:%02d".format(min, sec)
}

@Composable
private fun LibraryScreen() {
    val context = LocalContext.current
    val dao = remember { LibraryDatabase.get(context).library() }
    val scope = rememberCoroutineScope()
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

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            DefaultSubscription.ensureDefaultSubscribed(context, dao)
        }
    }

    DisposableEffect(context) {
        val future = MediaController.Builder(context, SessionToken(context, ComponentName(context, PlaybackService::class.java))).buildAsync()
        future.addListener({
            if (!future.isCancelled) runCatching { future.get() }.onSuccess { player ->
                controller = player
                playing = player.isPlaying
                player.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        playing = player.isPlaying
                        duration = player.duration.takeIf { it > 0 && it != C.TIME_UNSET } ?: 0
                    }
                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        currentId = mediaItem?.mediaId
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
            if (currentId != null && controller != null) {
                Surface(
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    shadowElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Android 15 draws apps edge-to-edge, so keep the controls above the system navigation bar.
                    Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(
                            controller?.currentMediaItem?.mediaMetadata?.title?.toString().orEmpty(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            controller?.currentMediaItem?.mediaMetadata?.artist?.toString().orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (duration > 0) {
                            Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                Canvas(Modifier.fillMaxWidth().height(6.dp).align(Alignment.Center)) {
                                    drawLine(Color(0xFF3A3B45), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height)
                                    segments.forEach { ad ->
                                        val start = (ad.startMs.toFloat() / duration).coerceIn(0f, 1f) * size.width
                                        val end = (ad.endMs.toFloat() / duration).coerceIn(0f, 1f) * size.width
                                        drawLine(PocketCastsAdGold, Offset(start, size.height / 2), Offset(end, size.height / 2), size.height)
                                    }
                                }
                            }
                            Slider(
                                value = position.toFloat().coerceIn(0f, duration.toFloat()),
                                onValueChange = { controller?.seekTo(it.toLong()); position = it.toLong() },
                                valueRange = 0f..duration.toFloat(),
                                colors = SliderDefaults.colors(
                                    thumbColor = PocketCastsRed,
                                    activeTrackColor = PocketCastsRed,
                                    inactiveTrackColor = Color.Transparent
                                ),
                                modifier = Modifier.fillMaxWidth().height(32.dp)
                            )
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(formatTime(position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
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
                                    if (playing) controller?.pause() else controller?.play()
                                    playing = !playing
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

                            val currentAd = segments.firstOrNull { position >= it.startMs && position < it.endMs }
                            if (currentAd != null) {
                                TextButton(onClick = { controller?.seekTo(currentAd.endMs) }) {
                                    Text("Skip ad", color = PocketCastsAdGold, fontWeight = FontWeight.Bold)
                                }
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
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            if (selected == null) {
                Text("Podcasts", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)

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

                Text("Your podcasts", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
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
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .background(
                                            Brush.linearGradient(
                                                listOf(PocketCastsRed, Color(0xFF8B0000))
                                            ),
                                            shape = RoundedCornerShape(8.dp)
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = podcast.title.take(2).uppercase(),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White
                                    )
                                }
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
                                        for (ep in dao.episodesForPodcast(podcast.id)) {
                                            File(context.filesDir, "episodes/${ep.id}").deleteRecursively()
                                        }
                                        dao.deletePodcast(podcast.id)
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
                        Text(selected.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
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
                LazyColumn(Modifier.weight(1f)) {
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
                                        Text("${epSegments.size} ad segment(s) removed", color = PocketCastsAdGold, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
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
                                            TextButton(onClick = { scope.launch { enqueueEpisode(context, dao, episode) } }) {
                                                Text("Scan again", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }
                                episode.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                                Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    if (episode.localPath != null && File(episode.localPath).isFile) {
                                        Button(
                                            onClick = {
                                                controller?.let { player ->
                                                    player.setMediaItem(
                                                        MediaItem.Builder()
                                                            .setMediaId(episode.id)
                                                            .setUri(File(episode.localPath).toURI().toString())
                                                            .setMediaMetadata(MediaMetadata.Builder().setTitle(episode.title).setArtist(selected.title).build())
                                                            .build()
                                                    )
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
                                        Spacer(Modifier.width(8.dp))
                                        TextButton(onClick = {
                                            scope.launch {
                                                File(context.filesDir, "episodes/${episode.id}").deleteRecursively()
                                                dao.saveEpisode(episode.copy(status = "NOT_DOWNLOADED", localPath = null, fingerprintPath = null))
                                                dao.deleteSegments(episode.id)
                                            }
                                        }) { Text("Delete", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                    if (episode.status !in listOf("DOWNLOADING", "QUEUED", "PROCESSING", "READY")) {
                                        Button(
                                            onClick = { scope.launch { enqueueEpisode(context, dao, episode) } },
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
