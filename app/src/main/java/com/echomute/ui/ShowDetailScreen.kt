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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
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
    onDownloadEpisode: (Episode) -> Unit,
    onCancelEpisode: (Episode) -> Unit,
    onAddToQueue: (Episode) -> Unit,
    onDeleteDownload: (Episode) -> Unit,
    onToggleFinished: (Episode) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = UnspliceMint
                )
                Spacer(Modifier.width(6.dp))
                Text("All podcasts", color = UnspliceMint, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.weight(1f))
            IconButton(enabled = !refreshing, onClick = onRefresh) {
                if (refreshing) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = UnspliceMint)
                } else {
                    Icon(Icons.Filled.Refresh, contentDescription = "Refresh episodes", tint = UnspliceIvory)
                }
            }
            IconButton(
                onClick = onTogglePodcastSettings,
                modifier = Modifier.testTag("toggle-podcast-settings")
            ) {
                Icon(
                    Icons.Filled.Settings,
                    contentDescription = "Podcast settings",
                    tint = if (podcastSettingsOpen) UnspliceMint else UnspliceIvory
                )
            }
        }
        LazyColumn(
            Modifier.weight(1f).testTag("episodes"),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item(key = "show-header") {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = UnspliceSurface,
                        border = BorderStroke(1.dp, UnspliceOutline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                PodcastArtwork(podcast.title, podcast.artworkUrl, size = 68.dp, cornerRadius = 14.dp)
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        podcast.title,
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        formatShowEpisodeCount(episodes.size, unplayedCount),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            Spacer(Modifier.height(14.dp))
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = UnspliceSurfaceElevated,
                                border = BorderStroke(1.dp, UnspliceOutline)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Filled.Shield,
                                        contentDescription = null,
                                        tint = if (podcast.autoSkip) UnspliceMint else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                        Text("Automatically skip detected ads", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "Detected ad breaks are skipped automatically during playback.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = podcast.autoSkip,
                                        onCheckedChange = { enabled -> onUpdatePodcast(podcast.copy(autoSkip = enabled)) },
                                        colors = SwitchDefaults.colors(
                                            checkedThumbColor = UnspliceOnMint,
                                            checkedTrackColor = UnspliceMint
                                        )
                                    )
                                }
                            }
                            AnimatedVisibility(visible = podcastSettingsOpen) {
                                Column {
                                    HorizontalDivider(Modifier.padding(vertical = 12.dp), color = UnspliceOutline)
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
                                        Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Auto-download new episodes", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
                                        Switch(
                                            checked = podcast.autoDownload,
                                            onCheckedChange = { enabled -> onUpdatePodcast(podcast.copy(autoDownload = enabled)) },
                                            colors = SwitchDefaults.colors(
                                                checkedThumbColor = UnspliceOnMint,
                                                checkedTrackColor = UnspliceMint
                                            )
                                        )
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    OutlinedButton(
                                        onClick = onRequestRemovePodcast,
                                        shape = RoundedCornerShape(12.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Unsubscribe & delete downloads", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("All", "Unplayed", "Downloaded").forEach { filterOption ->
                                FilterChip(
                                    selected = episodeFilter == filterOption,
                                    onClick = { onFilterChange(filterOption) },
                                    label = { Text(filterOption, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = UnspliceMintContainer,
                                        selectedLabelColor = UnspliceMint
                                    ),
                                    modifier = Modifier.testTag("filter-$filterOption")
                                )
                            }
                        }
                        IconButton(onClick = onToggleSearch) {
                            Icon(
                                if (episodeSearchOpen) Icons.Filled.Close else Icons.Filled.Search,
                                contentDescription = "Search episodes",
                                tint = if (episodeSearchOpen) UnspliceMint else UnspliceIvory
                            )
                        }
                        IconButton(onClick = onToggleSort, modifier = Modifier.testTag("sort-episodes")) {
                            Icon(
                                Icons.Filled.SwapVert,
                                contentDescription = if (oldestFirst) "Sorted oldest first" else "Sorted newest first",
                                tint = if (oldestFirst) UnspliceMint else LocalContentColor.current
                            )
                        }
                    }
                    AnimatedVisibility(visible = episodeSearchOpen) {
                        UnspliceSearchField(
                            value = episodeQuery,
                            onValueChange = onQueryChange,
                            label = "Search episodes",
                            modifier = Modifier.testTag("episode-search")
                        )
                    }
                }
            }
            if (visibleEpisodes.isEmpty()) {
                item(key = "empty-episodes") {
                    EmptyStateCard(
                        icon = Icons.Filled.Search,
                        title = "No matching episodes",
                        subtitle = "Try clearing your filter or search query to see all episodes."
                    )
                }
            } else {
                items(visibleEpisodes, key = { it.id }) { episode ->
                    EpisodeCard(
                        episode = episode,
                        autoSkip = podcast.autoSkip,
                        epSegments = segmentsByEpisode[episode.id].orEmpty(),
                        activeProgress = activeProgressMap[episode.id],
                        hasTranscript = File(filesDir, "episodes/${episode.id}/transcript.tsv").isFile,
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
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onAddToQueue: () -> Unit,
    onDeleteDownload: () -> Unit,
    onToggleFinished: () -> Unit
) {
    var dragAccumulated by remember(episode.id) { mutableFloatStateOf(0f) }
    val swipeOffset = dragAccumulated.coerceIn(-120f, 120f)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
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
        shape = RoundedCornerShape(16.dp),
        color = UnspliceSurface,
        border = BorderStroke(1.dp, UnspliceOutline)
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
                            style = MaterialTheme.typography.labelMedium,
                            color = if (episode.finished) UnspliceMint.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant,
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
                        tint = if (episode.starred) UnspliceAmber else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                episode.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = if (episode.finished) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.72f) else UnspliceIvory,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
            val epDuration = episode.durationMs
            if (!episode.finished && episode.positionMs > 0 && epDuration != null && epDuration > 0) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { (episode.positionMs.toFloat() / epDuration).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)),
                    color = UnspliceMint,
                    trackColor = UnspliceTrack
                )
            }
            if (!episode.description.isNullOrBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    if (notesExpanded) "Hide show notes" else "Show notes",
                    style = MaterialTheme.typography.labelMedium,
                    color = UnspliceMint,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onToggleNotes)
                        .padding(vertical = 2.dp)
                        .testTag("notes-${episode.id}")
                )
                AnimatedVisibility(visible = notesExpanded) {
                    Column(Modifier.padding(top = 6.dp)) {
                        Text(
                            episode.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        val timestamps = remember(episode.description) { extractTimestamps(episode.description) }
                        if (timestamps.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                timestamps.forEach { ts ->
                                    AssistChip(
                                        onClick = { onSeekTimestamp(ts.positionMs) },
                                        label = { Text(ts.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) }
                                    )
                                }
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
                Spacer(Modifier.height(8.dp))
                Text(
                    statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = UnspliceMint,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.testTag("episode-progress-${episode.id}")
                )
                Spacer(Modifier.height(4.dp))
                if (activeProgress?.percent != null) {
                    LinearProgressIndicator(
                        progress = { (activeProgress.percent / 100f).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)),
                        color = UnspliceMint,
                        trackColor = UnspliceTrack
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)),
                        color = UnspliceMint,
                        trackColor = UnspliceTrack
                    )
                }
            }
            if (episode.status == "READY") {
                Spacer(Modifier.height(8.dp))
                if (epSegments.isNotEmpty()) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = UnspliceAmberContainer,
                        border = BorderStroke(1.dp, UnspliceAmber.copy(alpha = 0.35f)),
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onToggleSegments)
                            .testTag("toggle-segments-${episode.id}")
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                formatAdSummary(epSegments, autoSkip).orEmpty(),
                                color = UnspliceAmber,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(Modifier.width(6.dp))
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
                            shape = RoundedCornerShape(12.dp),
                            color = UnspliceSurfaceElevated,
                            border = BorderStroke(1.dp, UnspliceOutline),
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        ) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                epSegments.forEachIndexed { idx, seg ->
                                    if (idx > 0) HorizontalDivider(color = UnspliceOutline)
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        val label = when (seg.source) {
                                            "marker" -> "Ad break"
                                            "chapter" -> "Chapter"
                                            "transcript" -> "Sponsor read"
                                            else -> "Repeat"
                                        }
                                        val manual = if (seg.confidence < AUTO_SKIP_CONFIDENCE) " • manual skip" else ""
                                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                            Text(
                                                "${formatTime(seg.startMs)} – ${formatTime(seg.endMs)}",
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                            Text(
                                                "$label$manual",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        OutlinedButton(
                                            onClick = { onKeepSegment(seg) },
                                            shape = RoundedCornerShape(20.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                            border = BorderStroke(1.dp, UnspliceOutline)
                                        ) {
                                            Text("Keep audio", style = MaterialTheme.typography.labelSmall, color = UnspliceIvory, maxLines = 1, softWrap = false)
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "Scan again",
                            style = MaterialTheme.typography.labelMedium,
                            color = UnspliceMint,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable(onClick = onScanAgain)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
                if (hasTranscript) {
                    Spacer(Modifier.height(6.dp))
                    StatusPill(
                        text = "View transcript",
                        containerColor = UnspliceSurfaceElevated,
                        contentColor = UnspliceMint,
                        borderColor = UnspliceOutline,
                        icon = Icons.Filled.Subtitles,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onOpenTranscript)
                    )
                }
            }
            episode.error?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onPlay,
                    shape = RoundedCornerShape(22.dp),
                    contentPadding = PaddingValues(start = 14.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = UnspliceMint, contentColor = UnspliceOnMint)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Play", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
                val isWorking = activeProgress != null || episode.status in listOf("DOWNLOADING", "QUEUED", "PROCESSING")
                val downloaded = episode.localPath != null && File(episode.localPath).isFile
                if (isWorking) {
                    OutlinedButton(
                        modifier = Modifier.testTag("cancel-${episode.id}"),
                        shape = RoundedCornerShape(22.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        border = BorderStroke(1.dp, UnspliceOutline),
                        onClick = onCancel
                    ) {
                        Text("Cancel", maxLines = 1, softWrap = false, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else if (episode.status != "READY") {
                    FilledTonalButton(
                        onClick = onDownload,
                        shape = RoundedCornerShape(22.dp),
                        contentPadding = PaddingValues(start = 12.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = UnspliceSurfaceElevated,
                            contentColor = UnspliceIvory
                        )
                    ) {
                        Icon(
                            if (episode.status == "FAILED") Icons.Filled.Refresh else Icons.Filled.Download,
                            contentDescription = null,
                            modifier = Modifier.size(17.dp)
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(if (episode.status == "FAILED") "Retry" else "Download", maxLines = 1, softWrap = false)
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(
                    modifier = Modifier.testTag("add-${episode.title}"),
                    onClick = onAddToQueue
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.PlaylistAdd,
                        contentDescription = "Add to Up Next",
                        tint = UnspliceIvory
                    )
                }
                if (!isWorking && downloaded) {
                    IconButton(onClick = onDeleteDownload) {
                        Icon(
                            Icons.Filled.DeleteOutline,
                            contentDescription = "Delete download",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
