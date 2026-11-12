package com.echomute

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Podcasts
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun LibraryTab(
    version: String?,
    podcasts: List<Podcast>,
    unplayedCounts: Map<String, Int>,
    gridLayout: Boolean,
    update: AppRelease?,
    onToggleLayout: () -> Unit,
    onOpenStats: () -> Unit,
    onOpenSettings: () -> Unit,
    onDownloadUpdate: (AppRelease) -> Unit,
    onSelectPodcast: (String) -> Unit,
    onRequestRemovePodcast: (Podcast) -> Unit,
    onOpenDiscover: () -> Unit
) {
    val totalUnplayed = remember(unplayedCounts) { unplayedCounts.values.sum() }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    if (!version.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = UnspliceSurfaceElevated,
                            border = BorderStroke(1.dp, UnspliceOutline)
                        ) {
                            Text(
                                "v$version",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                if (podcasts.isNotEmpty()) {
                    Text(
                        formatLibrarySummary(podcasts.size, totalUnplayed),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
            IconButton(onClick = onToggleLayout, modifier = Modifier.testTag("toggle-library-layout")) {
                Icon(
                    if (gridLayout) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                    contentDescription = "Toggle grid layout",
                    tint = UnspliceIvory
                )
            }
            IconButton(onClick = onOpenStats, modifier = Modifier.testTag("open-stats")) {
                Icon(Icons.Filled.Insights, contentDescription = "Listening stats", tint = UnspliceIvory)
            }
            IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("open-settings")) {
                Icon(Icons.Filled.Tune, contentDescription = "Audio and storage settings", tint = UnspliceIvory)
            }
        }
        update?.let { release ->
            Surface(
                color = UnspliceMintContainer,
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(1.dp, UnspliceMint.copy(alpha = 0.35f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .testTag("update-banner")
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = UnspliceMint, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Unsplice v${release.version} is available", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Tap download to update in place.",
                            style = MaterialTheme.typography.bodySmall,
                            color = UnspliceOnMintContainer.copy(alpha = 0.85f)
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = { onDownloadUpdate(release) },
                        shape = RoundedCornerShape(20.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = UnspliceMint,
                            contentColor = UnspliceOnMint
                        )
                    ) {
                        Text("Download", fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                    }
                }
            }
        }
        if (podcasts.isEmpty()) {
            Box(Modifier.weight(1f).padding(16.dp), contentAlignment = Alignment.Center) {
                EmptyStateCard(
                    icon = Icons.Filled.Podcasts,
                    title = "No podcasts yet",
                    subtitle = "Find shows in Discover or paste an RSS feed URL to start listening without ads.",
                    actionLabel = "Browse Discover",
                    onAction = onOpenDiscover
                )
            }
        } else if (gridLayout) {
            val rows = remember(podcasts) { podcasts.chunked(3) }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                items(rows.size) { rowIndex ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (podcast in rows[rowIndex]) {
                            val unplayed = unplayedCounts[podcast.id] ?: 0
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable { onSelectPodcast(podcast.id) }
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box {
                                    PodcastArtwork(podcast.title, podcast.artworkUrl, size = 96.dp, cornerRadius = 14.dp)
                                    if (unplayed > 0) {
                                        Box(
                                            Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(5.dp)
                                                .background(UnspliceMint, CircleShape)
                                                .padding(horizontal = 6.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                "$unplayed",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = UnspliceOnMint,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    podcast.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        repeat(3 - rows[rowIndex].size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        } else {
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(podcasts, key = { it.id }) { podcast ->
                    val unplayed = unplayedCounts[podcast.id] ?: 0
                    var menuOpen by remember(podcast.id) { mutableStateOf(false) }
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onSelectPodcast(podcast.id) },
                        shape = RoundedCornerShape(16.dp),
                        color = UnspliceSurface,
                        border = BorderStroke(1.dp, UnspliceOutline)
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            PodcastArtwork(podcast.title, podcast.artworkUrl, size = 56.dp, cornerRadius = 12.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    podcast.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Icon(
                                        Icons.Filled.Shield,
                                        contentDescription = null,
                                        tint = if (podcast.autoSkip) UnspliceMint else MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        if (podcast.autoSkip) "Auto-skip on" else "Auto-skip off",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (podcast.autoSkip) UnspliceMint else MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                            if (unplayed > 0) {
                                Surface(
                                    shape = RoundedCornerShape(50),
                                    color = UnspliceSurfaceElevated,
                                    border = BorderStroke(1.dp, UnspliceOutline)
                                ) {
                                    Text(
                                        "$unplayed",
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = UnspliceIvory,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                            }
                            Box {
                                IconButton(onClick = { menuOpen = true }) {
                                    Icon(
                                        Icons.Filled.MoreVert,
                                        contentDescription = "More options for ${podcast.title}",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                DropdownMenu(
                                    expanded = menuOpen,
                                    onDismissRequest = { menuOpen = false }
                                ) {
                                    DropdownMenuItem(
                                        text = { Text("Remove", color = MaterialTheme.colorScheme.error) },
                                        onClick = {
                                            menuOpen = false
                                            onRequestRemovePodcast(podcast)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
