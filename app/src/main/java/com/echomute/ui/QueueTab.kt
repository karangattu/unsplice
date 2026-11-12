package com.echomute

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import kotlin.math.roundToInt

@Composable
internal fun QueueTab(
    upcoming: List<MediaItem>,
    currentId: String?,
    podcastsByTitle: Map<String, Podcast>,
    onPlayItem: (MediaItem) -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
    onFinishItem: (Int, MediaItem) -> Unit,
    onRemoveItem: (Int) -> Unit
) {
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            Text("Up Next", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                formatQueueSummary(upcoming.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        if (upcoming.isEmpty()) {
            Box(Modifier.weight(1f).padding(16.dp), contentAlignment = Alignment.Center) {
                EmptyStateCard(
                    icon = Icons.AutoMirrored.Filled.QueueMusic,
                    title = "Your queue is empty",
                    subtitle = "Add episodes from your library to listen to them in order."
                )
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(upcoming.size) { index ->
                    val item = upcoming[index]
                    val title = item.mediaMetadata.title?.toString().orEmpty()
                    val artist = item.mediaMetadata.artist?.toString().orEmpty()
                    val podcast = podcastsByTitle[artist]
                    val isCurrent = item.mediaId == currentId
                    var dragAccumulated by remember(item.mediaId) { mutableFloatStateOf(0f) }
                    val swipeOffset = dragAccumulated.coerceIn(-120f, 0f)
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .offset { IntOffset((swipeOffset * 0.35f).roundToInt(), 0) }
                            .testTag("queue-$title")
                            .pointerInput(item.mediaId) {
                                detectHorizontalDragGestures(
                                    onDragStart = { dragAccumulated = 0f },
                                    onDragCancel = { dragAccumulated = 0f },
                                    onDragEnd = {
                                        if (dragAccumulated < -160f) onFinishItem(index, item)
                                        dragAccumulated = 0f
                                    },
                                    onHorizontalDrag = { _, dragAmount -> dragAccumulated += dragAmount }
                                )
                            },
                        color = if (isCurrent) UnspliceSurfaceElevated else UnspliceSurface,
                        border = BorderStroke(1.dp, if (isCurrent) UnspliceMint.copy(alpha = 0.45f) else UnspliceOutline),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PodcastArtwork(
                                title = artist.ifBlank { title },
                                url = podcast?.artworkUrl,
                                size = 44.dp,
                                cornerRadius = 10.dp
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f).clickable { onPlayItem(item) }) {
                                if (isCurrent) {
                                    Text(
                                        "CURRENT EPISODE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = UnspliceMint,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                Text(title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val published = item.toQueueItem().publishedAt?.let(::formatPublished)
                                val meta = listOfNotNull(artist.takeIf { it.isNotBlank() }, published).joinToString(" \u2022 ")
                                if (meta.isNotBlank()) {
                                    Text(
                                        meta,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(enabled = index > 1, onClick = { onMoveUp(index) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Move $title up")
                            }
                            IconButton(enabled = index > 0 && index < upcoming.lastIndex, onClick = { onMoveDown(index) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Move $title down")
                            }
                            IconButton(
                                modifier = Modifier.size(36.dp).testTag("finish-$title"),
                                onClick = { onFinishItem(index, item) }
                            ) {
                                Icon(Icons.Filled.Check, contentDescription = "Mark $title finished", tint = UnspliceMint)
                            }
                            IconButton(onClick = { onRemoveItem(index) }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.Close, contentDescription = "Remove $title from Up Next")
                            }
                        }
                    }
                }
            }
        }
    }
}
