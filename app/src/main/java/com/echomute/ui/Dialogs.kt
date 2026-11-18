package com.echomute

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun TranscriptDialog(
    episodeTitle: String,
    lines: List<TranscriptLine>,
    currentPositionMs: Long,
    onSeek: (Long) -> Unit,
    onMarkAd: (TranscriptLine) -> Unit,
    onDismiss: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(lines, query) { filterTranscriptLines(lines, query) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = UnspliceSurface,
        contentColor = UnspliceIvory
    ) {
        DarkSheetSystemBars()
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .padding(start = 20.dp, end = 20.dp, bottom = 20.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).padding(end = 8.dp)) {
                    Text("Transcript", style = MaterialTheme.typography.titleLarge, color = UnspliceIvory)
                    Text(
                        episodeTitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = UnspliceSlate,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close transcript", tint = UnspliceSlate)
                }
            }
            Spacer(Modifier.height(10.dp))
            UnspliceSearchField(
                value = query,
                onValueChange = { query = it },
                label = "Search transcript",
                modifier = Modifier.padding(bottom = 10.dp).testTag("transcript-search")
            )
            if (filtered.isEmpty()) {
                Text(
                    "No matching transcript lines.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = UnspliceSlate,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            } else {
                LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(filtered, key = { it.startMs }) { line ->
                        val active = currentPositionMs in line.startMs..line.endMs
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (active) UnspliceMintContainer else UnspliceSurfaceElevated.copy(alpha = 0.55f))
                                .clickable { onSeek(line.startMs) }
                                .padding(vertical = 8.dp, horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                formatTime(line.startMs),
                                style = MaterialTheme.typography.labelSmall.tabular(),
                                color = if (active) UnspliceMint else UnspliceAmber,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.width(52.dp)
                            )
                            Text(
                                line.text,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (active) UnspliceIvory else UnspliceIvory.copy(alpha = 0.88f),
                                modifier = Modifier.weight(1f)
                            )
                            TextButton(onClick = { onMarkAd(line) }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                Text("Flag ad", style = MaterialTheme.typography.labelSmall, color = UnspliceAmber, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ListeningStatsDialog(
    stats: ListeningStats,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = UnspliceSurface,
        contentColor = UnspliceIvory
    ) {
        DarkSheetSystemBars()
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Listening Stats", style = MaterialTheme.typography.titleLarge, color = UnspliceIvory, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close stats", tint = UnspliceSlate)
                }
            }
            val cardShape = RoundedCornerShape(18.dp)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(cardShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF103E32), Color(0xFF122742))))
                    .border(1.dp, UnspliceMint.copy(alpha = 0.35f), cardShape)
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(Icons.Filled.AutoAwesome, contentDescription = null, tint = UnspliceAmber, modifier = Modifier.size(16.dp))
                        Text(
                            "TOTAL TIME SAVED",
                            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.9.sp),
                            color = UnspliceMint,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        formatCompactDuration(stats.totalSavedMs).ifEmpty { "0s" },
                        style = MaterialTheme.typography.headlineMedium.tabular(),
                        color = UnspliceIvory,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = UnspliceSurfaceElevated,
                border = BorderStroke(1.dp, UnspliceHairline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatLine(Icons.Filled.Headphones, "Total listening time", formatCompactDuration(stats.totalListenedMs).ifEmpty { "0s" }, UnspliceIvory)
                    HorizontalDivider(color = UnspliceHairline)
                    StatLine(Icons.Filled.FastForward, "Ads & intros skipped", formatCompactDuration(stats.adSkippedMs).ifEmpty { "0s" }, UnspliceAmber)
                    StatLine(Icons.Filled.Speed, "Variable speed saved", formatCompactDuration(stats.speedSavedMs).ifEmpty { "0s" }, UnspliceMint)
                    StatLine(Icons.Filled.GraphicEq, "Trim silence saved", formatCompactDuration(stats.silenceSavedMs).ifEmpty { "0s" }, UnspliceSlate)
                }
            }
        }
    }
}

@Composable
private fun StatLine(icon: ImageVector, label: String, value: String, highlight: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .background(highlight.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = highlight, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = UnspliceSlate, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.tabular(), fontWeight = FontWeight.Bold, color = highlight)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsDialog(
    trimSilence: Boolean,
    voiceBoost: Boolean,
    autoDeletePlayed: Boolean,
    autoDownloadLimit: Int,
    storageList: List<PodcastStorageUsage>,
    version: String?,
    update: AppRelease?,
    updateChecking: Boolean,
    updateStatus: String?,
    updateDownload: UpdateDownload,
    onTrimSilenceChange: (Boolean) -> Unit,
    onVoiceBoostChange: (Boolean) -> Unit,
    onAutoDeleteChange: (Boolean) -> Unit,
    onAutoDownloadLimitChange: () -> Unit,
    onClearPodcastStorage: (PodcastStorageUsage) -> Unit,
    onCheckForUpdates: () -> Unit,
    onDownloadUpdate: (AppRelease) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = UnspliceSurface,
        contentColor = UnspliceIvory
    ) {
        DarkSheetSystemBars()
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", style = MaterialTheme.typography.titleLarge, color = UnspliceIvory, modifier = Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "Close settings", tint = UnspliceSlate)
                }
            }
            SettingsSectionCard(title = "Playback & Audio") {
                SettingsSwitchRow("Trim silence globally", trimSilence, onTrimSilenceChange)
                HorizontalDivider(color = UnspliceHairline)
                SettingsSwitchRow("Voice boost (volume leveling)", voiceBoost, onVoiceBoostChange)
                HorizontalDivider(color = UnspliceHairline)
                SettingsSwitchRow("Delete episodes after playing", autoDeletePlayed, onAutoDeleteChange)
                HorizontalDivider(color = UnspliceHairline)
                SettingValueRow(
                    label = "Auto-download limit",
                    value = if (autoDownloadLimit == 0) "Off" else "$autoDownloadLimit episodes",
                    onClick = onAutoDownloadLimitChange
                )
            }
            SettingsSectionCard(title = "Downloaded Storage") {
                if (storageList.isEmpty()) {
                    Text(
                        "No downloaded episodes on disk.",
                        style = MaterialTheme.typography.bodySmall,
                        color = UnspliceSlate,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                } else {
                    storageList.forEachIndexed { index, usage ->
                        if (index > 0) HorizontalDivider(color = UnspliceHairline)
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(
                                    usage.title,
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold,
                                    color = UnspliceIvory,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    "${usage.episodeCount} ep • ${formatMegabytes(usage.bytes)} MB",
                                    style = MaterialTheme.typography.labelSmall.tabular(),
                                    color = UnspliceSlate
                                )
                            }
                            TextButton(onClick = { onClearPodcastStorage(usage) }) {
                                Text("Clear", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
            SettingsSectionCard(title = "Updates") {
                Text(
                    "Installed version ${version?.let { "v$it" } ?: "unknown"}",
                    style = MaterialTheme.typography.bodySmall.tabular(),
                    color = UnspliceSlate
                )
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    TextButton(
                        onClick = onCheckForUpdates,
                        enabled = !updateChecking,
                        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp)
                    ) {
                        Text(
                            if (updateChecking) "Checking…" else "Check for updates",
                            color = UnspliceMint,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    if (updateChecking) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = UnspliceMint)
                    }
                }
                if (!updateStatus.isNullOrBlank()) {
                    Text(updateStatus, style = MaterialTheme.typography.bodySmall, color = UnspliceSlate)
                }
                update?.let { release ->
                    Spacer(Modifier.height(4.dp))
                    FilledTonalButton(
                        onClick = { onDownloadUpdate(release) },
                        enabled = !updateDownload.active,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(50),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = UnspliceMint,
                            contentColor = UnspliceOnMint
                        )
                    ) {
                        Text(
                            if (updateDownload.active) "Downloading…" else "Download & install v${release.version}",
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                    if (updateDownload.active) {
                        Spacer(Modifier.height(6.dp))
                        UnspliceProgressBar(fraction = updateDownload.fraction, height = 4.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionCard(
    title: String,
    content: @Composable () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.8.sp),
            color = UnspliceSlate,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 4.dp)
        )
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = UnspliceSurfaceElevated,
            border = BorderStroke(1.dp, UnspliceHairline),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                content()
            }
        }
    }
}

@Composable
private fun SettingsSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = UnspliceIvory, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = UnspliceOnMint,
                checkedTrackColor = UnspliceMint,
                uncheckedThumbColor = UnspliceSlate,
                uncheckedTrackColor = UnspliceSurfaceBright,
                uncheckedBorderColor = UnspliceOutline
            )
        )
    }
}

@Composable
internal fun RemovePodcastDialog(
    podcast: Podcast,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = UnspliceSurfaceElevated,
        titleContentColor = UnspliceIvory,
        textContentColor = UnspliceSlate,
        title = { Text("Remove ${podcast.title}?") },
        text = { Text("This removes the subscription and deletes any downloaded episodes for this podcast.") },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("confirm-remove-podcast"),
                onClick = onConfirm
            ) {
                Text("Remove", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = UnspliceSlate, maxLines = 1, softWrap = false)
            }
        }
    )
}
