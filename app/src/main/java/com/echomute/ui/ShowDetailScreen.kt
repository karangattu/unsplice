package com.echomute

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.math.roundToInt

@Composable
internal fun ShowDetailScreen(
    podcast: Podcast,
    episodes: List<Episode>,
    visibleEpisodes: List<Episode>,
    unplayedCount: Int,
    segmentsByEpisode: Map<String, List<AdSegment>>,
    activeProgressMap: Map<String, EpisodeProgress>,
    filesDir: File,
    refreshing: Boolean,
    podcastSettingsOpen: Boolean,
    episodeFilter: String,
    episodeQuery: String,
    episodeSearchOpen: Boolean,
    oldestFirst: Boolean,
    expandedSegmentSet: Set<String>,
    expandedNotesSet: Set<String>,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onTogglePodcastSettings: () -> Unit,
    onUpdatePodcast: (Podcast) -> Unit,
    onRequestRemovePodcast: () -> Unit,
    onFilterChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onQueryChange: (String) -> Unit,
    onToggleSort: () -> Unit,
    onToggleNotes: (String) -> Unit,
    onToggleSegments: (String) -> Unit,
    onToggleStar: (Episode) -> Unit,
    onSeekTimestamp: (Episode, Long) -> Unit,
    onKeepSegment: (AdSegment) -> Unit,
    onScanAgain: (Episode) -> Unit,
    onOpenTranscript: (Episode) -> Unit,
    onPlayEpisode: (Episode) -> Unit,
    onPlayNext: (Episode) -> Unit,
    onDownloadEpisode: (Episode) -> Unit,
    onCancelEpisode: (Episode) -> Unit,
    onAddToQueue: (Episode) -> Unit,
    onDeleteDownload: (Episode) -> Unit,
    onToggleFinished: (Episode) -> Unit
) {
    val auraColor = rememberPodcastAuraColor(podcast.title, podcast.artworkUrl)
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(auraColor.copy(alpha = 0.72f), auraColor.copy(alpha = 0.56f))
                    )
                )
                .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = UnspliceIvory
                )
                Spacer(Modifier.width(6.dp))
                Text("All podcasts", color = UnspliceIvory, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.weight(1f))
            Surface(
                onClick = onRefresh,
                enabled = !refreshing,
                shape = CircleShape,
                color = Color(0x38070B14),
                border = BorderStroke(1.dp, UnspliceHairline),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (refreshing) {
                        CircularProgressIndicator(modifier = Modifier.size(17.dp), strokeWidth = 2.dp, color = UnspliceMint)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh episodes", tint = UnspliceIvory, modifier = Modifier.size(19.dp))
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Surface(
                onClick = onTogglePodcastSettings,
                shape = CircleShape,
                color = if (podcastSettingsOpen) UnspliceMintContainer else Color(0x38070B14),
                border = BorderStroke(1.dp, if (podcastSettingsOpen) UnspliceMint.copy(alpha = 0.45f) else UnspliceHairline),
                modifier = Modifier.size(40.dp).testTag("toggle-podcast-settings")
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = "Podcast settings",
                        tint = if (podcastSettingsOpen) UnspliceMint else UnspliceIvory,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }
        LazyColumn(
            Modifier.weight(1f).testTag("episodes"),
            contentPadding = PaddingValues(bottom = 80.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "show-header") {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.verticalGradient(
                                    listOf(auraColor.copy(alpha = 0.56f), UnspliceMidnight)
                                )
                            )
                            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 14.dp)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PodcastArtwork(podcast.title, podcast.artworkUrl, size = 84.dp, cornerRadius = 16.dp)
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        podcast.title,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = UnspliceIvory,
                                        maxLines = 3,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        formatShowEpisodeCount(episodes.size, unplayedCount),
                                        style = MaterialTheme.typography.bodySmall.tabular(),
                                        color = UnspliceIvory.copy(alpha = 0.78f)
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = UnspliceSurfaceElevated.copy(alpha = 0.78f),
                                border = BorderStroke(1.dp, UnspliceHairline),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(
                                            Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(if (podcast.autoSkip) UnspliceMintContainer else UnspliceSurfaceBright),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                Icons.Filled.Shield,
                                                contentDescription = null,
                                                tint = if (podcast.autoSkip) UnspliceMint else UnspliceMuted,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                        Spacer(Modifier.width(12.dp))
                                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                            Text(
                                                "Automatically skip detected ads",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                color = UnspliceIvory
                                            )
                                            Text(
                                                "Detected ad breaks are skipped automatically during playback.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = UnspliceSlate
                                            )
                                        }
                                        Switch(
                                            checked = podcast.autoSkip,
                                            onCheckedChange = { enabled -> onUpdatePodcast(podcast.copy(autoSkip = enabled)) },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = UnspliceOnMint,
                                                checkedTrackColor = UnspliceMint,
                                                uncheckedThumbColor = UnspliceSlate,
                                                uncheckedTrackColor = UnspliceSurfaceBright,
                                                uncheckedBorderColor = UnspliceOutline
                                            )
                                        )
                                    }
                                    AnimatedVisibility(visible = podcastSettingsOpen) {
                                        Column {
                                            HorizontalDivider(Modifier.padding(vertical = 10.dp), color = UnspliceHairline)
                                            SettingValueRow("Skip intro", "${podcast.skipIntroSec}s") {
                                                val next = nextCycleInt(podcast.skipIntroSec, listOf(0, 15, 30, 45, 60))
                                                onUpdatePodcast(podcast.copy(skipIntroSec = next))
                                            }
                                            SettingValueRow("Skip outro", "${podcast.skipOutroSec}s") {
                                                val next = nextCycleInt(podcast.skipOutroSec, listOf(0, 15, 30, 45, 60))
                                                onUpdatePodcast(podcast.copy(skipOutroSec = next))
                                            }
                                            SettingValueRow("Playback speed", podcast.customSpeed?.let { "${it}x" } ?: "Default") {
                                                onUpdatePodcast(podcast.copy(customSpeed = nextCustomSpeed(podcast.customSpeed)))
                                            }
                                            Row(
                                                Modifier.fillMaxWidth().padding(vertical = 4.dp, horizontal = 4.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("Auto-download new episodes", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
                                                Switch(
                                                    checked = podcast.autoDownload,
                                                    onCheckedChange = { enabled -> onUpdatePodcast(podcast.copy(autoDownload = enabled)) },
                                                    colors = SwitchDefaults.colors(
                                                        checkedThumbColor = UnspliceOnMint,
                                                        checkedTrackColor = UnspliceMint,
                                                        uncheckedThumbColor = UnspliceSlate,
                                                        uncheckedTrackColor = UnspliceSurfaceBright,
                                                        uncheckedBorderColor = UnspliceOutline
                                                    )
                                                )
                                            }
                                            Spacer(Modifier.height(8.dp))
                                            OutlinedButton(
                                                onClick = onRequestRemovePodcast,
                                                shape = RoundedCornerShape(12.dp),
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.45f)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("Unsubscribe & delete downloads", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("All", "Unplayed", "Downloaded").forEach { filterOption ->
                                UnspliceFilterChip(
                                    selected = episodeFilter == filterOption,
                                    label = filterOption,
                                    onClick = { onFilterChange(filterOption) },
                                    modifier = Modifier.testTag("filter-$filterOption")
                                )
                            }
                        }
                        IconButton(onClick = onToggleSearch) {
                            Icon(
                                if (episodeSearchOpen) Icons.Filled.Close else Icons.Filled.Search,
                                contentDescription = "Search episodes",
                                tint = if (episodeSearchOpen) UnspliceMint else UnspliceSlate
                            )
                        }
                        IconButton(onClick = onToggleSort, modifier = Modifier.testTag("sort-episodes")) {
                            Icon(
                                Icons.Filled.SwapVert,
                                contentDescription = if (oldestFirst) "Sorted oldest first" else "Sorted newest first",
                                tint = if (oldestFirst) UnspliceMint else UnspliceSlate
                            )
                        }
                    }
                    AnimatedVisibility(visible = episodeSearchOpen) {
                        UnspliceSearchField(
                            value = episodeQuery,
                            onValueChange = onQueryChange,
                            label = "Search episodes",
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .testTag("episode-search")
                        )
                    }
                }
            }
            if (visibleEpisodes.isEmpty()) {
                item(key = "empty-episodes") {
                    EmptyStateCard(
                        icon = Icons.Filled.Search,
                        title = "No matching episodes",
                        subtitle = "Try clearing your filter or search query to see all episodes.",
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            } else {
                items(visibleEpisodes, key = { it.id }) { episode ->
                    val hasTranscript = remember(episode.id, episode.status) {
                        File(filesDir, "episodes/${episode.id}/transcript.tsv").isFile
                    }
                    EpisodeCard(
                        episode = episode,
                        autoSkip = podcast.autoSkip,
                        epSegments = segmentsByEpisode[episode.id].orEmpty(),
                        activeProgress = activeProgressMap[episode.id],
                        hasTranscript = hasTranscript,
                        notesExpanded = episode.id in expandedNotesSet,
                        segmentsExpanded = episode.id in expandedSegmentSet,
                        onToggleNotes = { onToggleNotes(episode.id) },
                        onToggleSegments = { onToggleSegments(episode.id) },
                        onToggleStar = { onToggleStar(episode) },
                        onSeekTimestamp = { ms -> onSeekTimestamp(episode, ms) },
                        onKeepSegment = onKeepSegment,
                        onScanAgain = { onScanAgain(episode) },
                        onOpenTranscript = { onOpenTranscript(episode) },
                        onPlay = { onPlayEpisode(episode) },
                        onPlayNext = { onPlayNext(episode) },
                        onDownload = { onDownloadEpisode(episode) },
                        onCancel = { onCancelEpisode(episode) },
                        onAddToQueue = { onAddToQueue(episode) },
                        onDeleteDownload = { onDeleteDownload(episode) },
                        onToggleFinished = { onToggleFinished(episode) }
                    )
                }
            }
        }
    }
}

@Composable
private fun EpisodeCard(
    episode: Episode,
    autoSkip: Boolean,
    epSegments: List<AdSegment>,
    activeProgress: EpisodeProgress?,
    hasTranscript: Boolean,
    notesExpanded: Boolean,
    segmentsExpanded: Boolean,
    onToggleNotes: () -> Unit,
    onToggleSegments: () -> Unit,
    onToggleStar: () -> Unit,
    onSeekTimestamp: (Long) -> Unit,
    onKeepSegment: (AdSegment) -> Unit,
    onScanAgain: () -> Unit,
    onOpenTranscript: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onAddToQueue: () -> Unit,
    onDeleteDownload: () -> Unit,
    onToggleFinished: () -> Unit
) {
    var dragAccumulated by remember(episode.id) { mutableFloatStateOf(0f) }
    val swipeOffset = dragAccumulated.coerceIn(-120f, 120f)
    val downloaded = remember(episode.id, episode.localPath, episode.status) {
        episode.localPath != null && File(episode.localPath).isFile
    }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .offset { IntOffset((swipeOffset * 0.35f).roundToInt(), 0) }
            .pointerInput(episode.id, episode.finished) {
                detectHorizontalDragGestures(
                    onDragStart = { dragAccumulated = 0f },
                    onDragCancel = { dragAccumulated = 0f },
                    onDragEnd = {
                        if (dragAccumulated > 160f) {
                            onAddToQueue()
                        } else if (dragAccumulated < -160f) {
                            onToggleFinished()
                        }
                        dragAccumulated = 0f
                    },
                    onHorizontalDrag = { _, dragAmount -> dragAccumulated += dragAmount }
                )
            },
        shape = RoundedCornerShape(18.dp),
        color = UnspliceSurface,
        border = BorderStroke(1.dp, UnspliceHairline)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                val details = listOfNotNull(
                    episode.publishedAt?.let(::formatPublished),
                    progressLabel(episode.positionMs, episode.durationMs, episode.finished)
                )
                Row(
                    Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (episode.finished) {
                        Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = UnspliceMint, modifier = Modifier.size(14.dp))
                    }
                    if (details.isNotEmpty()) {
                        Text(
                            details.joinToString(" \u2022 "),
                            style = MaterialTheme.typography.labelMedium.tabular(),
                            color = if (episode.finished) UnspliceMint.copy(alpha = 0.85f) else UnspliceSlate,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag("episode-details-${episode.id}")
                        )
                    }
                }
                IconButton(
                    modifier = Modifier.size(30.dp).testTag("star-${episode.id}"),
                    onClick = onToggleStar
                ) {
                    Icon(
                        if (episode.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                        contentDescription = if (episode.starred) "Unstar episode" else "Star episode",
                        tint = if (episode.starred) UnspliceAmber else UnspliceMuted,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                episode.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (episode.finished) UnspliceSlate else UnspliceIvory,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            val epDuration = episode.durationMs
            if (!episode.finished && episode.positionMs > 0 && epDuration != null && epDuration > 0) {
                Spacer(Modifier.height(10.dp))
                UnspliceProgressBar(fraction = episode.positionMs.toFloat() / epDuration, height = 3.dp)
            }
            if (!episode.description.isNullOrBlank()) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        if (notesExpanded) "Hide show notes" else "Show notes",
                        style = MaterialTheme.typography.labelMedium,
                        color = UnspliceSlate,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable(onClick = onToggleNotes)
                            .padding(vertical = 2.dp)
                            .testTag("notes-${episode.id}")
                    )
                    if (hasTranscript && episode.status == "READY") {
                        StatusPill(
                            text = "View transcript",
                            containerColor = UnspliceSurfaceElevated,
                            contentColor = UnspliceIvory,
                            borderColor = UnspliceHairline,
                            icon = Icons.Filled.Subtitles,
                            modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onOpenTranscript)
                        )
                    }
                }
                AnimatedVisibility(visible = notesExpanded) {
                    Column(Modifier.padding(top = 8.dp)) {
                        Text(
                            episode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = UnspliceSlate
                        )
                        val timestamps = remember(episode.description) { extractTimestamps(episode.description) }
                        if (timestamps.isNotEmpty()) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                timestamps.forEach { ts ->
                                    AssistChip(
                                        onClick = { onSeekTimestamp(ts.positionMs) },
                                        shape = RoundedCornerShape(50),
                                        colors = AssistChipDefaults.assistChipColors(
                                            containerColor = UnspliceSurfaceElevated,
                                            labelColor = UnspliceIvory
                                        ),
                                        border = AssistChipDefaults.assistChipBorder(enabled = true, borderColor = UnspliceHairline),
                                        label = { Text(ts.label, style = MaterialTheme.typography.labelSmall.tabular(), maxLines = 1, softWrap = false) }
                                    )
                                }
                            }
                        }
                    }
                }
            } else if (hasTranscript && episode.status == "READY") {
                Spacer(Modifier.height(8.dp))
                StatusPill(
                    text = "View transcript",
                    containerColor = UnspliceSurfaceElevated,
                    contentColor = UnspliceIvory,
                    borderColor = UnspliceHairline,
                    icon = Icons.Filled.Subtitles,
                    modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onOpenTranscript)
                )
            }
            val statusText = activeProgress?.summary() ?: when (episode.status) {
                "PROCESSING" -> "Finding ads…"
                "DOWNLOADING" -> "Downloading episode"
                "QUEUED" -> "Queued"
                else -> null
            }
            if (statusText != null) {
                Spacer(Modifier.height(10.dp))
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = UnspliceMint,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag("episode-progress-${episode.id}")
                )
                Spacer(Modifier.height(5.dp))
                UnspliceProgressBar(
                    fraction = activeProgress?.percent?.let { it / 100f },
                    height = 4.dp
                )
            }
            if (episode.status == "READY") {
                Spacer(Modifier.height(10.dp))
                if (epSegments.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = UnspliceAmberContainer,
                        border = BorderStroke(1.dp, UnspliceAmber.copy(alpha = 0.28f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onToggleSegments)
                            .testTag("toggle-segments-${episode.id}")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Filled.FastForward, contentDescription = null, tint = UnspliceAmber, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                formatAdSummary(epSegments, autoSkip).orEmpty(),
                                color = UnspliceAmber,
                                style = MaterialTheme.typography.labelMedium.tabular(),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(4.dp))
                            Icon(
                                if (segmentsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (segmentsExpanded) "Hide ad segments" else "Show ad segments",
                                tint = UnspliceAmber,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                    AnimatedVisibility(visible = segmentsExpanded) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = UnspliceSurfaceElevated,
                            border = BorderStroke(1.dp, UnspliceHairline),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                epSegments.forEachIndexed { idx, seg ->
                                    if (idx > 0) HorizontalDivider(color = UnspliceHairline)
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        val manual = if (seg.confidence < AUTO_SKIP_CONFIDENCE) " • manual skip" else ""
                                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                            Text(
                                                "${formatTime(seg.startMs)} – ${formatTime(seg.endMs)}",
                                                style = MaterialTheme.typography.bodySmall.tabular(),
                                                fontWeight = FontWeight.SemiBold,
                                                color = UnspliceIvory,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                            Text(
                                                "${formatAdSegmentLabel(seg.source)}$manual",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = UnspliceSlate,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        FilledTonalButton(
                                            onClick = { onKeepSegment(seg) },
                                            shape = RoundedCornerShape(50),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            colors = ButtonDefaults.filledTonalButtonColors(
                                                containerColor = UnspliceSurfaceBright,
                                                contentColor = UnspliceIvory
                                            )
                                        ) {
                                            Text("Keep audio", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (activeProgress == null) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "No ads found",
                            style = MaterialTheme.typography.labelMedium,
                            color = UnspliceMuted
                        )
                        TextButton(
                            onClick = onScanAgain,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, tint = UnspliceSlate, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Scan again", style = MaterialTheme.typography.labelMedium, color = UnspliceSlate, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
            episode.error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPlay,
                    shape = RoundedCornerShape(50),
                    contentPadding = PaddingValues(start = 14.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = UnspliceMint, contentColor = UnspliceOnMint)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
                val isWorking = activeProgress != null || episode.status in listOf("DOWNLOADING", "QUEUED", "PROCESSING")
                if (isWorking) {
                    OutlinedButton(
                        modifier = Modifier.testTag("cancel-${episode.id}"),
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        border = BorderStroke(1.dp, UnspliceHairline),
                        onClick = onCancel
                    ) {
                        Text("Cancel", maxLines = 1, softWrap = false, color = UnspliceSlate)
                    }
                } else if (episode.status != "READY") {
                    FilledTonalButton(
                        onClick = onDownload,
                        shape = RoundedCornerShape(50),
                        contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = UnspliceSurfaceElevated,
                            contentColor = UnspliceIvory
                        )
                    ) {
                        Icon(
                            if (episode.status == "FAILED") Icons.Filled.Refresh else Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(if (episode.status == "FAILED") "Retry" else "Download", maxLines = 1, softWrap = false)
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    modifier = Modifier.size(36.dp).testTag("play-next-${episode.title}"),
                    onClick = onPlayNext
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.PlaylistPlay,
                        contentDescription = "Play ${episode.title} next",
                        tint = UnspliceSlate
                    )
                }
                IconButton(
                    modifier = Modifier.size(36.dp).testTag("add-${episode.title}"),
                    onClick = onAddToQueue
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = "Add to Up Next",
                        tint = UnspliceSlate
                    )
                }
                if (!isWorking && downloaded) {
                    IconButton(
                        modifier = Modifier.size(36.dp),
                        onClick = onDeleteDownload
                    ) {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = "Delete download",
                            tint = UnspliceMuted
                        )
                    }
                }
            }
        }
    }
}
