package com.echomute

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import kotlin.math.roundToInt
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
internal fun QueueTab(
    upcoming: List<MediaItem>,
    currentId: String?,
    episodesById: Map<String, Episode>,
    podcastsById: Map<String, Podcast>,
    onPlayItem: (MediaItem) -> Unit,
    onMoveItem: (String, String) -> Unit,
    onFinishItem: (String) -> Unit,
    onRemoveItem: (String) -> Unit
) {
    val listState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        onMoveItem(from.key as String, to.key as String)
    }
    val haptics = LocalHapticFeedback.current
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 10.dp)) {
            Text("Up Next", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                formatQueueSummary(upcoming.size),
                style = MaterialTheme.typography.bodySmall.tabular(),
                color = UnspliceSlate,
                modifier = Modifier.padding(top = 3.dp)
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
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(upcoming, key = { _, item -> item.mediaId }) { index, item ->
                    val id = item.mediaId
                    val isCurrent = id == currentId
                    val canReorder = index > 0 && !isCurrent && upcoming.size > 2
                    ReorderableItem(reorderState, key = id, enabled = canReorder) { isDragging ->
                        val title = item.mediaMetadata.title?.toString().orEmpty()
                        val artist = item.mediaMetadata.artist?.toString().orEmpty()
                        val episode = episodesById[id]
                        val podcast = episode?.let { podcastsById[it.podcastId] }
                            ?: podcastsById.values.firstOrNull { it.title == artist }
                        val show = (podcast?.title ?: artist).ifBlank { title }
                        val published = (episode?.publishedAt ?: item.toQueueItem().publishedAt)?.let(::formatPublished)
                        var swipeDistance by remember { mutableFloatStateOf(0f) }
                        val finishItem by rememberUpdatedState { onFinishItem(id) }
                        val cardBrush = if (isCurrent) {
                            Brush.horizontalGradient(
                                listOf(UnspliceMintContainer.copy(alpha = 0.55f), UnspliceSurfaceElevated)
                            )
                        } else {
                            null
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset { IntOffset((swipeDistance.coerceIn(-120f, 0f) * 0.35f).roundToInt(), 0) }
                                .testTag("queue-$title")
                                .pointerInput(id) {
                                    detectHorizontalDragGestures(
                                        onDragStart = { swipeDistance = 0f },
                                        onDragCancel = { swipeDistance = 0f },
                                        onDragEnd = {
                                            if (swipeDistance < -160f) finishItem()
                                            swipeDistance = 0f
                                        },
                                        onHorizontalDrag = { _, amount -> swipeDistance += amount }
                                    )
                                },
                            color = when {
                                isDragging -> UnspliceSurfaceElevated
                                isCurrent -> Color.Transparent
                                else -> UnspliceSurface
                            },
                            border = BorderStroke(
                                1.dp,
                                when {
                                    isDragging -> UnspliceMint
                                    isCurrent -> UnspliceMint.copy(alpha = 0.35f)
                                    else -> UnspliceHairline
                                }
                            ),
                            shadowElevation = if (isDragging) 10.dp else 0.dp,
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .then(if (cardBrush != null) Modifier.background(cardBrush) else Modifier)
                                    .padding(start = 12.dp, top = 11.dp, bottom = 11.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(Modifier.clearAndSetSemantics { contentDescription = show }) {
                                    PodcastArtwork(title = show, url = podcast?.artworkUrl, size = 50.dp, cornerRadius = 12.dp)
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clickable(onClickLabel = "Play $title") { onPlayItem(item) }
                                ) {
                                    if (isCurrent) {
                                        Text(
                                            "CURRENT EPISODE",
                                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
                                            color = UnspliceMint,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(bottom = 2.dp)
                                        )
                                    }
                                    Text(
                                        title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = UnspliceIvory,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    if (published != null) {
                                        Text(
                                            published,
                                            style = MaterialTheme.typography.bodySmall.tabular(),
                                            color = UnspliceSlate,
                                            modifier = Modifier.padding(top = 3.dp)
                                        )
                                    }
                                }
                                if (canReorder) {
                                    Box(
                                        modifier = Modifier
                                            .size(48.dp)
                                            .testTag("reorder-$title")
                                            .draggableHandle(
                                                onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                                            ),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            Icons.Filled.DragIndicator,
                                            contentDescription = null,
                                            tint = if (isDragging) UnspliceMint else UnspliceMuted
                                        )
                                    }
                                }
                                QueueEpisodeActions(
                                    title = title,
                                    onMoveUp = if (canReorder && index > 1) {
                                        { onMoveItem(id, upcoming[index - 1].mediaId) }
                                    } else null,
                                    onMoveDown = if (canReorder && index < upcoming.lastIndex) {
                                        { onMoveItem(id, upcoming[index + 1].mediaId) }
                                    } else null,
                                    onFinish = finishItem,
                                    onRemove = { onRemoveItem(id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueEpisodeActions(
    title: String,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    onFinish: () -> Unit,
    onRemove: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Queue actions for $title", tint = UnspliceSlate)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (onMoveUp != null) {
                DropdownMenuItem(
                    text = { Text("Move up") },
                    leadingIcon = { Icon(Icons.Filled.KeyboardArrowUp, contentDescription = null) },
                    onClick = { expanded = false; onMoveUp() }
                )
            }
            if (onMoveDown != null) {
                DropdownMenuItem(
                    text = { Text("Move down") },
                    leadingIcon = { Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null) },
                    onClick = { expanded = false; onMoveDown() }
                )
            }
            DropdownMenuItem(
                text = { Text("Mark finished") },
                leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null, tint = UnspliceMint) },
                onClick = { expanded = false; onFinish() }
            )
            DropdownMenuItem(
                text = { Text("Remove from Up Next") },
                leadingIcon = { Icon(Icons.Filled.Close, contentDescription = null) },
                onClick = { expanded = false; onRemove() }
            )
        }
    }
}
