package com.echomute

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun MiniPlayerBar(
    title: String,
    artistName: String,
    artworkUrl: String?,
    playing: Boolean,
    position: Long,
    duration: Long,
    segments: List<AdSegment>,
    autoSkip: Boolean,
    currentAd: AdSegment?,
    showUndoSkip: Boolean,
    onTogglePlayPause: () -> Unit,
    onExpand: () -> Unit,
    onSkipAd: (AdSegment) -> Unit,
    onUndoSkip: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = UnspliceSurfaceElevated,
        border = BorderStroke(1.dp, if (currentAd != null) UnspliceAmber.copy(alpha = 0.55f) else UnspliceOutline),
        shadowElevation = 10.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpand)
                    .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PodcastArtwork(
                    title = artistName.ifBlank { title },
                    url = artworkUrl,
                    size = 42.dp,
                    cornerRadius = 10.dp
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (currentAd != null) {
                        Text(
                            formatAdRemainingLabel(currentAd, position),
                            style = MaterialTheme.typography.labelSmall,
                            color = UnspliceAmber,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            artistName.ifBlank { "Now Playing" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause playback" else "Resume playback",
                        tint = UnspliceIvory
                    )
                }
                IconButton(onClick = onExpand) {
                    Icon(
                        Icons.Filled.ExpandLess,
                        contentDescription = "Expand player",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (duration > 0) {
                Box(Modifier.padding(horizontal = 10.dp).padding(bottom = if (currentAd != null || showUndoSkip) 4.dp else 2.dp)) {
                    MiniPlaybackProgress(position, duration, segments, autoSkip)
                }
            }
            if (currentAd != null || showUndoSkip) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 10.dp, end = 10.dp, top = 2.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentAd != null) {
                        FilledTonalButton(
                            onClick = { onSkipAd(currentAd) },
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = UnspliceAmberContainer,
                                contentColor = UnspliceAmber
                            )
                        ) {
                            Icon(Icons.Filled.FastForward, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Skip ad", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                    }
                    if (showUndoSkip) {
                        OutlinedButton(
                            onClick = onUndoSkip,
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            border = BorderStroke(1.dp, UnspliceMint.copy(alpha = 0.5f))
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, tint = UnspliceMint, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(5.dp))
                            Text("Undo skip", color = UnspliceMint, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun NowPlayingSheet(
    visible: Boolean,
    title: String,
    artistName: String,
    artworkUrl: String?,
    playing: Boolean,
    position: Long,
    duration: Long,
    speed: Float,
    trimSilence: Boolean,
    voiceBoost: Boolean,
    sleepLabel: String,
    segments: List<AdSegment>,
    chapters: List<Chapter>,
    autoSkip: Boolean,
    canToggleAutoSkip: Boolean,
    episodeStatus: String?,
    episodeProgress: EpisodeProgress?,
    showPlayerChapters: Boolean,
    showPlayerAds: Boolean,
    hasTranscript: Boolean,
    currentAd: AdSegment?,
    showUndoSkip: Boolean,
    onCollapse: () -> Unit,
    onOpenShow: (() -> Unit)?,
    onTogglePlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onStepSpeed: (Float) -> Unit,
    onCycleSpeed: () -> Unit,
    onToggleTrimSilence: () -> Unit,
    onToggleVoiceBoost: () -> Unit,
    onCycleSleepTimer: () -> Unit,
    onToggleAutoSkip: () -> Unit,
    onToggleChapters: () -> Unit,
    onToggleAdsPanel: () -> Unit,
    onOpenTranscript: () -> Unit,
    onToggleChapterSkip: (Chapter, Long, AdSegment?) -> Unit,
    onKeepSegment: (AdSegment) -> Unit,
    onScanAds: () -> Unit,
    onSkipAd: (AdSegment) -> Unit,
    onUndoSkip: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it },
        exit = slideOutVertically { it }
    ) {
        BackHandler(enabled = visible, onBack = onCollapse)
        var dragY by remember { mutableFloatStateOf(0f) }
        Surface(
            color = UnspliceMidnight,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectVerticalDragGestures(
                        onDragStart = { dragY = 0f },
                        onDragEnd = {
                            if (dragY > 140f) onCollapse()
                            dragY = 0f
                        },
                        onVerticalDrag = { _, dragAmount ->
                            if (dragAmount > 0) dragY += dragAmount
                        }
                    )
                }
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF142736), UnspliceMidnight, UnspliceMidnight)
                        )
                    )
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.22f))
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onCollapse) {
                            Icon(
                                Icons.Filled.ExpandMore,
                                contentDescription = "Collapse player",
                                tint = UnspliceIvory
                            )
                        }
                        Column(
                            Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                "NOW PLAYING",
                                style = MaterialTheme.typography.labelSmall,
                                color = UnspliceMint,
                                fontWeight = FontWeight.Bold
                            )
                            if (artistName.isNotBlank()) {
                                Text(
                                    artistName,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(Modifier.size(48.dp))
                    }
                    Spacer(Modifier.height(16.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(240.dp)
                            .background(
                                Brush.radialGradient(
                                    listOf(UnspliceMint.copy(alpha = 0.16f), Color.Transparent)
                                ),
                                CircleShape
                            )
                    ) {
                        PodcastArtwork(
                            title = artistName.ifBlank { title },
                            url = artworkUrl,
                            size = 212.dp,
                            cornerRadius = 20.dp
                        )
                    }
                    Spacer(Modifier.height(20.dp))
                    Text(
                        title,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (artistName.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            artistName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = UnspliceMint,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = if (onOpenShow != null) Modifier.clickable(onClick = onOpenShow) else Modifier
                        )
                    }
                    if (currentAd != null || showUndoSkip) {
                        Spacer(Modifier.height(12.dp))
                        if (currentAd != null) {
                            Text(
                                formatAdRemainingLabel(currentAd, position),
                                style = MaterialTheme.typography.labelMedium,
                                color = UnspliceAmber,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (currentAd != null) {
                                FilledTonalButton(
                                    onClick = { onSkipAd(currentAd) },
                                    shape = RoundedCornerShape(24.dp),
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = UnspliceAmberContainer,
                                        contentColor = UnspliceAmber
                                    )
                                ) {
                                    Icon(Icons.Filled.FastForward, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Skip ad", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                }
                            }
                            if (showUndoSkip) {
                                OutlinedButton(
                                    onClick = onUndoSkip,
                                    shape = RoundedCornerShape(24.dp),
                                    border = BorderStroke(1.dp, UnspliceMint.copy(alpha = 0.5f))
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, tint = UnspliceMint, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Undo skip", color = UnspliceMint, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    PlaybackProgress(position, duration, segments, chapters, autoSkip, onSeek)
                    Spacer(Modifier.height(16.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SeekButton(label = "15", mirrored = false, contentDescription = "Rewind 15 seconds") {
                            onSeek((position - 15_000).coerceAtLeast(0))
                        }
                        FilledIconButton(
                            onClick = onTogglePlayPause,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = UnspliceMint,
                                contentColor = UnspliceOnMint
                            ),
                            modifier = Modifier.size(68.dp)
                        ) {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play",
                                modifier = Modifier.size(36.dp)
                            )
                        }
                        SeekButton(label = "30", mirrored = true, contentDescription = "Forward 30 seconds") {
                            onSeek((position + 30_000).coerceAtMost(duration))
                        }
                    }
                    Spacer(Modifier.height(22.dp))
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = UnspliceSurface,
                        border = BorderStroke(1.dp, UnspliceOutline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = UnspliceSurfaceElevated,
                                    border = BorderStroke(1.dp, UnspliceOutline)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        IconButton(
                                            modifier = Modifier.size(36.dp).testTag("speed-down"),
                                            onClick = { onStepSpeed(-0.1f) }
                                        ) {
                                            Icon(Icons.Filled.Remove, contentDescription = "Decrease speed", modifier = Modifier.size(16.dp))
                                        }
                                        Text(
                                            "${speed}x",
                                            fontWeight = FontWeight.Bold,
                                            color = UnspliceMint,
                                            style = MaterialTheme.typography.labelLarge,
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier
                                                .clickable(onClick = onCycleSpeed)
                                                .padding(horizontal = 8.dp)
                                        )
                                        IconButton(
                                            modifier = Modifier.size(36.dp).testTag("speed-up"),
                                            onClick = { onStepSpeed(0.1f) }
                                        ) {
                                            Icon(Icons.Filled.Add, contentDescription = "Increase speed", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                                FilterChip(
                                    selected = sleepLabel != "Off",
                                    onClick = onCycleSleepTimer,
                                    leadingIcon = { Icon(Icons.Filled.Bedtime, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    label = { Text("Sleep: $sleepLabel", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = UnspliceMintContainer,
                                        selectedLabelColor = UnspliceMint,
                                        selectedLeadingIconColor = UnspliceMint
                                    )
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = autoSkip,
                                    enabled = canToggleAutoSkip,
                                    onClick = onToggleAutoSkip,
                                    leadingIcon = { Icon(Icons.Filled.Shield, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    label = { Text(if (autoSkip) "Auto-skip: ON" else "Auto-skip: OFF", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = UnspliceAmberContainer,
                                        selectedLabelColor = UnspliceAmber,
                                        selectedLeadingIconColor = UnspliceAmber
                                    )
                                )
                                FilterChip(
                                    selected = showPlayerAds,
                                    onClick = onToggleAdsPanel,
                                    leadingIcon = { Icon(Icons.Filled.FastForward, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    label = {
                                        val label = if (segments.isNotEmpty()) "Ad breaks (${segments.size})" else "Ad breaks"
                                        Text(label, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false)
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = UnspliceAmberContainer,
                                        selectedLabelColor = UnspliceAmber,
                                        selectedLeadingIconColor = UnspliceAmber
                                    )
                                )
                                FilterChip(
                                    selected = trimSilence,
                                    onClick = onToggleTrimSilence,
                                    leadingIcon = { Icon(Icons.Filled.GraphicEq, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    label = { Text("Trim silence", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = UnspliceMintContainer,
                                        selectedLabelColor = UnspliceMint,
                                        selectedLeadingIconColor = UnspliceMint
                                    )
                                )
                                FilterChip(
                                    selected = voiceBoost,
                                    onClick = onToggleVoiceBoost,
                                    leadingIcon = { Icon(Icons.Filled.RecordVoiceOver, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                    label = { Text("Voice boost", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = UnspliceMintContainer,
                                        selectedLabelColor = UnspliceMint,
                                        selectedLeadingIconColor = UnspliceMint
                                    )
                                )
                                if (chapters.isNotEmpty()) {
                                    FilterChip(
                                        selected = showPlayerChapters,
                                        onClick = onToggleChapters,
                                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                        label = { Text("Chapters (${chapters.size})", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) },
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = UnspliceMintContainer,
                                            selectedLabelColor = UnspliceMint,
                                            selectedLeadingIconColor = UnspliceMint
                                        )
                                    )
                                }
                                if (hasTranscript) {
                                    FilterChip(
                                        selected = false,
                                        onClick = onOpenTranscript,
                                        leadingIcon = { Icon(Icons.Filled.Subtitles, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                        label = { Text("Transcript", style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false) }
                                    )
                                }
                            }
                        }
                    }
                    if (showPlayerAds) {
                        Spacer(Modifier.height(14.dp))
                        val isScanning = episodeProgress != null || episodeStatus in listOf("QUEUED", "DOWNLOADING", "PROCESSING")
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = UnspliceSurface,
                            border = BorderStroke(1.dp, UnspliceOutline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "AD BREAKS",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = UnspliceAmber,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(
                                            if (autoSkip) "Auto-skip enabled for this show" else "Auto-skip disabled • manual skip only",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (!isScanning && episodeStatus != null) {
                                        TextButton(
                                            onClick = onScanAds,
                                            contentPadding = PaddingValues(horizontal = 8.dp)
                                        ) {
                                            Icon(Icons.Filled.Refresh, contentDescription = null, tint = UnspliceMint, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("Scan again", color = UnspliceMint, style = MaterialTheme.typography.labelMedium, maxLines = 1, softWrap = false)
                                        }
                                    }
                                }
                                if (isScanning) {
                                    val scanLabel = episodeProgress?.summary() ?: when (episodeStatus) {
                                        "PROCESSING" -> "Finding ads…"
                                        "DOWNLOADING" -> "Downloading episode…"
                                        else -> "Queued for ad scan…"
                                    }
                                    Text(scanLabel, style = MaterialTheme.typography.bodySmall, color = UnspliceMint, fontWeight = FontWeight.Medium)
                                    if (episodeProgress?.percent != null) {
                                        LinearProgressIndicator(
                                            progress = { (episodeProgress.percent / 100f).coerceIn(0f, 1f) },
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
                                } else if (segments.isEmpty()) {
                                    Text(
                                        "No ad breaks detected yet for this episode.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    segments.forEachIndexed { idx, seg ->
                                        if (idx > 0) HorizontalDivider(color = UnspliceOutline)
                                        val isCurrentSeg = position >= seg.startMs && position < seg.endMs
                                        val manual = if (seg.confidence < AUTO_SKIP_CONFIDENCE) " • manual skip" else ""
                                        Row(
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(RoundedCornerShape(10.dp))
                                                .background(if (isCurrentSeg) UnspliceAmberContainer else Color.Transparent)
                                                .clickable { onSeek(seg.startMs) }
                                                .padding(horizontal = 8.dp, vertical = 6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(Modifier.weight(1f).padding(end = 6.dp)) {
                                                Text(
                                                    "${formatTime(seg.startMs)} – ${formatTime(seg.endMs)}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = if (isCurrentSeg) UnspliceAmber else UnspliceIvory,
                                                    fontWeight = FontWeight.SemiBold,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                                Text(
                                                    "${formatAdSegmentLabel(seg.source)}$manual",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                            IconButton(
                                                onClick = { onSkipAd(seg) },
                                                modifier = Modifier.size(34.dp)
                                            ) {
                                                Icon(
                                                    Icons.Filled.SkipNext,
                                                    contentDescription = "Skip past ad segment",
                                                    tint = UnspliceAmber,
                                                    modifier = Modifier.size(20.dp)
                                                )
                                            }
                                            TextButton(
                                                onClick = { onKeepSegment(seg) },
                                                contentPadding = PaddingValues(horizontal = 8.dp)
                                            ) {
                                                Text("Keep", style = MaterialTheme.typography.labelSmall, color = UnspliceMint, maxLines = 1, softWrap = false)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (showPlayerChapters && chapters.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        val activeChapter = remember(chapters, position, duration) { currentChapter(chapters, position, duration) }
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = UnspliceSurface,
                            border = BorderStroke(1.dp, UnspliceOutline),
                            modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)
                        ) {
                            LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                items(chapters.size) { idx ->
                                    val ch = chapters[idx]
                                    val chEnd = ch.endMs ?: chapters.getOrNull(idx + 1)?.startMs ?: duration
                                    val skippedSeg = segments.firstOrNull { it.startMs == ch.startMs && it.source == "chapter" }
                                    val isActive = activeChapter?.startMs == ch.startMs
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isActive) UnspliceMintContainer else Color.Transparent)
                                            .clickable { onSeek(ch.startMs) }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            formatTime(ch.startMs),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isActive) UnspliceMint else MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.width(50.dp)
                                        )
                                        Text(
                                            ch.title,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isActive) UnspliceIvory else MaterialTheme.colorScheme.onSurface,
                                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        TextButton(
                                            onClick = { onToggleChapterSkip(ch, chEnd, skippedSeg) },
                                            contentPadding = PaddingValues(horizontal = 8.dp)
                                        ) {
                                            Text(
                                                if (skippedSeg != null) "Unskip" else "Skip",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = UnspliceAmber,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }
        }
    }
}
