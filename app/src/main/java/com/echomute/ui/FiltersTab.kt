package com.echomute

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

@Composable
internal fun FiltersTab(
    smartFilter: String,
    smartQuery: String,
    episodes: List<Episode>,
    podcastsById: Map<String, Podcast>,
    onSelectFilter: (String) -> Unit,
    onQueryChange: (String) -> Unit,
    onToggleStar: (Episode) -> Unit,
    onToggleFinished: (Episode) -> Unit,
    onPlayEpisode: (Episode, Podcast) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Filters", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Smart views across all your shows",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("New Releases", "In Progress", "Downloaded", "Starred").forEach { name ->
                    FilterChip(
                        selected = smartFilter == name,
                        onClick = { onSelectFilter(name) },
                        label = { Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = UnspliceMintContainer,
                            selectedLabelColor = UnspliceMint
                        ),
                        modifier = Modifier.testTag("smart-filter-$name")
                    )
                }
            }
            UnspliceSearchField(
                value = smartQuery,
                onValueChange = onQueryChange,
                label = "Filter episodes across all shows",
                modifier = Modifier.padding(top = 6.dp)
            )
        }
        if (episodes.isEmpty()) {
            Box(Modifier.weight(1f).padding(16.dp), contentAlignment = Alignment.Center) {
                EmptyStateCard(
                    icon = Icons.Filled.FilterList,
                    title = "No episodes in $smartFilter",
                    subtitle = "Episodes matching this view will appear here automatically."
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(episodes, key = { it.id }) { ep ->
                    val pod = podcastsById[ep.podcastId]
                    var dragAccumulated by remember(ep.id) { mutableFloatStateOf(0f) }
                    val swipeOffset = dragAccumulated.coerceIn(-120f, 0f)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset { IntOffset((swipeOffset * 0.35f).roundToInt(), 0) }
                            .pointerInput(ep.id, ep.finished) {
                                detectHorizontalDragGestures(
                                    onDragStart = { dragAccumulated = 0f },
                                    onDragCancel = { dragAccumulated = 0f },
                                    onDragEnd = {
                                        if (dragAccumulated < -160f) onToggleFinished(ep)
                                        dragAccumulated = 0f
                                    },
                                    onHorizontalDrag = { _, dragAmount -> dragAccumulated += dragAmount }
                                )
                            },
                        shape = RoundedCornerShape(16.dp),
                        color = UnspliceSurface,
                        border = BorderStroke(1.dp, UnspliceOutline)
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                PodcastArtwork(pod?.title ?: ep.title, pod?.artworkUrl, size = 46.dp, cornerRadius = 10.dp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(ep.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    val meta = listOfNotNull(
                                        pod?.title,
                                        ep.publishedAt?.let(::formatPublished),
                                        progressLabel(ep.positionMs, ep.durationMs, ep.finished)
                                    )
                                    Text(
                                        meta.joinToString(" \u2022 "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(onClick = { onToggleStar(ep) }) {
                                    Icon(
                                        if (ep.starred) Icons.Filled.Star else Icons.Filled.StarBorder,
                                        contentDescription = "Star episode",
                                        tint = if (ep.starred) UnspliceAmber else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Spacer(Modifier.width(2.dp))
                                FilledIconButton(
                                    onClick = { if (pod != null) onPlayEpisode(ep, pod) },
                                    shape = CircleShape,
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = UnspliceMintContainer,
                                        contentColor = UnspliceMint
                                    ),
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Icon(Icons.Filled.PlayArrow, contentDescription = "Play ${ep.title}", modifier = Modifier.size(20.dp))
                                }
                            }
                            val epDuration = ep.durationMs
                            if (!ep.finished && ep.positionMs > 0 && epDuration != null && epDuration > 0) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = { (ep.positionMs.toFloat() / epDuration).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)),
                                    color = UnspliceMint,
                                    trackColor = UnspliceTrack
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
