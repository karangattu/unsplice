package com.echomute

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = UnspliceSurface,
        title = { Text("Transcript • $episodeTitle", maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                UnspliceSearchField(
                    value = query,
                    onValueChange = { query = it },
                    label = "Search transcript",
                    modifier = Modifier.padding(bottom = 8.dp).testTag("transcript-search")
                )
                if (filtered.isEmpty()) {
                    Text("No matching transcript lines.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(filtered, key = { it.startMs }) { line ->
                            val active = currentPositionMs in line.startMs..line.endMs
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(if (active) UnspliceMintContainer else Color.Transparent)
                                    .clickable { onSeek(line.startMs) }
                                    .padding(vertical = 6.dp, horizontal = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    formatTime(line.startMs),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (active) UnspliceMint else UnspliceAmber,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.width(48.dp)
                                )
                                Text(
                                    line.text,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (active) UnspliceIvory else MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                TextButton(onClick = { onMarkAd(line) }, contentPadding = PaddingValues(horizontal = 6.dp)) {
                                    Text("Flag ad", style = MaterialTheme.typography.labelSmall, color = UnspliceAmber, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close", color = UnspliceMint, maxLines = 1, softWrap = false) }
        }
    )
}

@Composable
internal fun ListeningStatsDialog(
    stats: ListeningStats,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = UnspliceSurface,
        title = { Text("Listening Stats & Time Saved") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = UnspliceMintContainer,
                    border = BorderStroke(1.dp, UnspliceMint.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("TOTAL TIME SAVED", style = MaterialTheme.typography.labelSmall, color = UnspliceMint, fontWeight = FontWeight.Bold)
                        Text(
                            formatCompactDuration(stats.totalSavedMs).ifEmpty { "0s" },
                            style = MaterialTheme.typography.headlineMedium,
                            color = UnspliceIvory,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                StatLine("Total listening time", formatCompactDuration(stats.totalListenedMs).ifEmpty { "0s" })
                HorizontalDivider(color = UnspliceOutline)
                StatLine("Ads & intros skipped", formatCompactDuration(stats.adSkippedMs).ifEmpty { "0s" }, highlight = UnspliceAmber)
                StatLine("Variable speed saved", formatCompactDuration(stats.speedSavedMs).ifEmpty { "0s" })
                StatLine("Trim silence saved", formatCompactDuration(stats.silenceSavedMs).ifEmpty { "0s" })
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = UnspliceMint, maxLines = 1, softWrap = false) }
        }
    )
}

@Composable
private fun StatLine(label: String, value: String, highlight: Color = UnspliceIvory) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = highlight)
    }
}

@Composable
internal fun SettingsDialog(
    trimSilence: Boolean,
    voiceBoost: Boolean,
    autoDeletePlayed: Boolean,
    storageList: List<PodcastStorageUsage>,
    onTrimSilenceChange: (Boolean) -> Unit,
    onVoiceBoostChange: (Boolean) -> Unit,
    onAutoDeleteChange: (Boolean) -> Unit,
    onClearPodcastStorage: (PodcastStorageUsage) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = UnspliceSurface,
        title = { Text("Settings") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SettingsSwitchRow("Trim silence globally", trimSilence, onTrimSilenceChange)
                SettingsSwitchRow("Voice boost (volume leveling)", voiceBoost, onVoiceBoostChange)
                SettingsSwitchRow("Delete episodes after playing", autoDeletePlayed, onAutoDeleteChange)
                HorizontalDivider(color = UnspliceOutline)
                Text("Downloaded storage", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                if (storageList.isEmpty()) {
                    Text("No downloaded episodes on disk.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    storageList.forEach { usage ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(usage.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${usage.episodeCount} ep • ${formatMegabytes(usage.bytes)} MB", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { onClearPodcastStorage(usage) }) {
                                Text("Clear", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done", color = UnspliceMint, maxLines = 1, softWrap = false) }
        }
    )
}

@Composable
private fun SettingsSwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = UnspliceOnMint,
                checkedTrackColor = UnspliceMint
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
        containerColor = UnspliceSurface,
        title = { Text("Remove ${podcast.title}?") },
        text = { Text("This removes the subscription and deletes any downloaded episodes for this podcast.") },
        confirmButton = {
            TextButton(
                modifier = Modifier.testTag("confirm-remove-podcast"),
                onClick = onConfirm
            ) {
                Text("Remove", color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
            }
        }
    )
}
