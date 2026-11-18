package com.echomute

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
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
    latestPublishedByPodcast: Map<String, Long> = emptyMap(),
    gridLayout: Boolean,
    update: AppRelease?,
    updateDownload: UpdateDownload = UpdateDownload(),
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
                .padding(start = 20.dp, end = 16.dp, top = 14.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Library", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    if (!version.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = UnspliceSurfaceElevated,
                            border = BorderStroke(1.dp, UnspliceHairline)
                        ) {
                            Text(
                                "v$version",
                                style = MaterialTheme.typography.labelSmall.tabular(),
                                color = UnspliceSlate,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                if (podcasts.isNotEmpty()) {
                    Text(
                        formatLibrarySummary(podcasts.size, totalUnplayed),
                        style = MaterialTheme.typography.bodySmall.tabular(),
                        color = UnspliceSlate,
                        modifier = Modifier.padding(top = 3.dp)
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                HeaderActionButton(
                    icon = if (gridLayout) Icons.AutoMirrored.Filled.ViewList else Icons.Filled.GridView,
                    contentDescription = "Toggle grid layout",
                    onClick = onToggleLayout,
                    modifier = Modifier.testTag("toggle-library-layout")
                )
                HeaderActionButton(
                    icon = Icons.Filled.Insights,
                    contentDescription = "Listening stats",
                    onClick = onOpenStats,
                    modifier = Modifier.testTag("open-stats")
                )
                HeaderActionButton(
                    icon = Icons.Filled.Tune,
                    contentDescription = "Audio and storage settings",
                    onClick = onOpenSettings,
                    modifier = Modifier.testTag("open-settings")
                )
            }
        }
        update?.let { release ->
            val bannerShape = RoundedCornerShape(16.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(bannerShape)
                    .background(Brush.horizontalGradient(listOf(UnspliceMintContainer, UnspliceSurfaceElevated)))
                    .border(1.dp, UnspliceMint.copy(alpha = 0.32f), bannerShape)
                    .testTag("update-banner")
            ) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(UnspliceMint.copy(alpha = 0.14f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Filled.SystemUpdate, contentDescription = null, tint = UnspliceMint, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Unsplice v${release.version} is available", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                if (updateDownload.active) {
                                    val percent = updateDownload.fraction?.let { " ${(it * 100).toInt()}%" }.orEmpty()
                                    "Downloading update$percent…"
                                } else {
                                    "Tap download to update in place."
                                },
                                style = MaterialTheme.typography.bodySmall.tabular(),
                                color = UnspliceOnMintContainer.copy(alpha = 0.85f)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        FilledTonalButton(
                            onClick = { onDownloadUpdate(release) },
                            enabled = !updateDownload.active,
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = UnspliceMint,
                                contentColor = UnspliceOnMint
                            )
                        ) {
                            Text(
                                if (updateDownload.active) "Downloading…" else "Download",
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                    if (updateDownload.active) {
                        Spacer(Modifier.height(10.dp))
                        UnspliceProgressBar(fraction = updateDownload.fraction, height = 4.dp)
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
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(rows.size) { rowIndex ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        for (podcast in rows[rowIndex]) {
                            val unplayed = unplayedCounts[podcast.id] ?: 0
                            Column(
                                Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { onSelectPodcast(podcast.id) }
                                    .padding(bottom = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                BoxWithConstraints(Modifier.fillMaxWidth()) {
                                    PodcastArtwork(podcast.title, podcast.artworkUrl, size = maxWidth, cornerRadius = 16.dp)
                                    if (unplayed > 0) {
                                        Box(
                                            Modifier
                                                .align(Alignment.TopEnd)
                                                .padding(6.dp)
                                                .clip(CircleShape)
                                                .background(Color(0xD9070B14))
                                                .border(1.dp, UnspliceMint.copy(alpha = 0.55f), CircleShape)
                                                .padding(horizontal = 7.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                "$unplayed",
                                                style = MaterialTheme.typography.labelSmall.tabular(),
                                                color = UnspliceMint,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    podcast.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = UnspliceIvory,
                                    textAlign = TextAlign.Center,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 2.dp)
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
                contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp)
            ) {
                itemsIndexed(podcasts, key = { _, it -> it.id }) { index, podcast ->
                    val unplayed = unplayedCounts[podcast.id] ?: 0
                    val latestPublished = latestPublishedByPodcast[podcast.id]?.let { formatPublished(it) }
                    var menuOpen by remember(podcast.id) { mutableStateOf(false) }
                    if (index > 0) {
                        HorizontalDivider(
                            color = UnspliceHairline,
                            thickness = 0.8.dp,
                            modifier = Modifier.padding(start = 94.dp, end = 16.dp)
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelectPodcast(podcast.id) }
                            .padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PodcastArtwork(podcast.title, podcast.artworkUrl, size = 60.dp, cornerRadius = 14.dp)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                podcast.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = UnspliceIvory,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                Icon(
                                    Icons.Filled.Shield,
                                    contentDescription = null,
                                    tint = if (podcast.autoSkip) UnspliceMint.copy(alpha = 0.85f) else UnspliceMuted,
                                    modifier = Modifier.size(13.dp)
                                )
                                val subtitleText = buildString {
                                    append(if (podcast.autoSkip) "Auto-skip on" else "Auto-skip off")
                                    if (latestPublished != null) {
                                        append(" \u2022 ")
                                        append(latestPublished)
                                    }
                                }
                                Text(
                                    subtitleText,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = UnspliceSlate,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        if (unplayed > 0) {
                            Spacer(Modifier.width(8.dp))
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = UnspliceSurfaceElevated,
                                border = BorderStroke(1.dp, UnspliceHairline)
                            ) {
                                Text(
                                    "$unplayed",
                                    style = MaterialTheme.typography.labelMedium.tabular(),
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
                                    tint = UnspliceMuted
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

@Composable
private fun HeaderActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = UnspliceSurface,
        border = BorderStroke(1.dp, UnspliceHairline),
        modifier = modifier.size(40.dp)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, tint = UnspliceIvory, modifier = Modifier.size(19.dp))
        }
    }
}
