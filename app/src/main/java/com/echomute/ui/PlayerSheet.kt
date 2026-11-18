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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
        shape = RoundedCornerShape(18.dp),
        color = UnspliceSurfaceElevated,
        border = BorderStroke(1.dp, if (currentAd != null) UnspliceAmber.copy(alpha = 0.55f) else UnspliceHairline),
        shadowElevation = 8.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Column {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onExpand)
                    .padding(start = 10.dp, end = 6.dp, top = 8.dp, bottom = 7.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                PodcastArtwork(
                    title = artistName.ifBlank { title },
                    url = artworkUrl,
                    size = 44.dp,
                    cornerRadius = 11.dp
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = UnspliceIvory,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (currentAd != null) {
                        Text(
                            formatAdRemainingLabel(currentAd, position),
                            style = MaterialTheme.typography.labelSmall.tabular(),
                            color = UnspliceAmber,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    } else {
                        Text(
                            artistName.ifBlank { "Now Playing" },
                            style = MaterialTheme.typography.bodySmall,
                            color = UnspliceSlate,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                PlayingEqualizer(playing = playing, modifier = Modifier.padding(horizontal = 6.dp))
                FilledIconButton(
                    onClick = onTogglePlayPause,
                    shape = CircleShape,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = UnspliceSurfaceBright,
                        contentColor = UnspliceIvory
                    ),
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playing) "Pause playback" else "Resume playback",
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(onClick = onExpand, modifier = Modifier.size(38.dp)) {
                    Icon(
                        Icons.Filled.ExpandLess,
                        contentDescription = "Expand player",
                        tint = UnspliceSlate
                    )
                }
            }
            if (duration > 0) {
                Box(Modifier.padding(horizontal = 12.dp).padding(bottom = if (currentAd != null || showUndoSkip) 6.dp else 4.dp)) {
                    MiniPlaybackProgress(position, duration, segments, autoSkip)
                }
            }
            if (currentAd != null || showUndoSkip) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (currentAd != null) {
                        FilledTonalButton(
                            onClick = { onSkipAd(currentAd) },
                            shape = RoundedCornerShape(50),
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
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                            border = BorderStroke(1.dp, UnspliceMint.copy(alpha = 0.45f))
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

@OptIn(ExperimentalMaterial3Api::class)
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
    onShowAdBreaks: () -> Unit,
    onDismissAdBreaks: () -> Unit,
    onOpenTranscript: () -> Unit,
    onToggleChapterSkip: (Chapter, Long, AdSegment?) -> Unit,
    onKeepSegment: (AdSegment) -> Unit,
    onScanAds: () -> Unit,
    onSkipAd: (AdSegment) -> Unit,
    onUndoSkip: () -> Unit
) {
    val auraColor = rememberPodcastAuraColor(artistName.ifBlank { title }, artworkUrl)
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
                            0.0f to auraColor.copy(alpha = 0.90f),
                            0.45f to auraColor.copy(alpha = 0.28f),
                            0.78f to UnspliceMidnight,
                            1.0f to UnspliceMidnight
                        )
                    )
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        Modifier
                            .width(36.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(UnspliceIvory.copy(alpha = 0.25f))
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(top = 6.dp),
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
                                style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                                color = UnspliceIvory.copy(alpha = 0.65f),
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.size(48.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(252.dp)
                            .background(
                                Brush.radialGradient(
                                    listOf(auraColor.copy(alpha = 0.55f), UnspliceMint.copy(alpha = 0.10f), Color.Transparent)
                                ),
                                CircleShape
                            )
                    ) {
                        PodcastArtwork(
                            title = artistName.ifBlank { title },
                            url = artworkUrl,
                            size = 228.dp,
                            cornerRadius = 22.dp
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = UnspliceIvory,
                            maxLines = 3,
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
                    }
                    if (currentAd != null || showUndoSkip) {
                        Spacer(Modifier.height(14.dp))
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = if (currentAd != null) UnspliceAmberContainer.copy(alpha = 0.9f) else UnspliceMintContainer.copy(alpha = 0.7f),
                            border = BorderStroke(
                                1.dp,
                                if (currentAd != null) UnspliceAmber.copy(alpha = 0.35f) else UnspliceMint.copy(alpha = 0.35f)
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Text(
                                    if (currentAd != null) formatAdRemainingLabel(currentAd, position) else "Skipped ad segment",
                                    style = MaterialTheme.typography.labelMedium.tabular(),
                                    color = if (currentAd != null) UnspliceAmber else UnspliceMint,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.weight(1f)
                                )
                                if (currentAd != null) {
                                    FilledTonalButton(
                                        onClick = { onSkipAd(currentAd) },
                                        shape = RoundedCornerShape(50),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        colors = ButtonDefaults.filledTonalButtonColors(
                                            containerColor = UnspliceAmber,
                                            contentColor = Color(0xFF261904)
                                        )
                                    ) {
                                        Icon(Icons.Filled.FastForward, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Skip ad", fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                                    }
                                }
                                if (showUndoSkip) {
                                    OutlinedButton(
                                        onClick = onUndoSkip,
                                        shape = RoundedCornerShape(50),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                        border = BorderStroke(1.dp, UnspliceMint.copy(alpha = 0.5f))
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null, tint = UnspliceMint, modifier = Modifier.size(15.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Undo skip", color = UnspliceMint, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
                                    }
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    PlaybackProgress(position, duration, segments, chapters, autoSkip, onSeek)
                    Spacer(Modifier.height(14.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(30.dp, Alignment.CenterHorizontally),
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
                            modifier = Modifier.size(72.dp)
                        ) {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = if (playing) "Pause" else "Play",
                                modifier = Modifier.size(38.dp)
                            )
                        }
                        SeekButton(label = "30", mirrored = true, contentDescription = "Forward 30 seconds") {
                            onSeek((position + 30_000).coerceAtMost(duration))
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Surface(
                        onClick = onShowAdBreaks,
                        shape = RoundedCornerShape(16.dp),
                        color = UnspliceSurface,
                        border = BorderStroke(1.dp, UnspliceHairline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(UnspliceAmberContainer),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Filled.FastForward, contentDescription = null, tint = UnspliceAmber, modifier = Modifier.size(18.dp))
                            }
                            Column(Modifier.weight(1f)) {
                                Text("Ad breaks", style = MaterialTheme.typography.labelLarge, color = UnspliceIvory)
                                Text(
                                    remember(segments, autoSkip) { formatAdSummary(segments, autoSkip) }
                                        ?: "Review or scan for ads",
                                    style = MaterialTheme.typography.bodySmall.tabular(),
                                    color = UnspliceSlate
                                )
                            }
                            Icon(
                                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                contentDescription = null,
                                tint = UnspliceMuted
                            )
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        color = UnspliceSurface,
                        border = BorderStroke(1.dp, UnspliceHairline),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(UnspliceSurfaceElevated)
                                        .padding(horizontal = 4.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        modifier = Modifier.size(34.dp).testTag("speed-down"),
                                        onClick = { onStepSpeed(-0.1f) }
                                    ) {
                                        Icon(Icons.Filled.Remove, contentDescription = "Decrease speed", tint = UnspliceIvory, modifier = Modifier.size(16.dp))
                                    }
                                    Text(
                                        "${speed}x",
                                        fontWeight = FontWeight.Bold,
                                        color = UnspliceMint,
                                        style = MaterialTheme.typography.labelLarge.tabular(),
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier
                                            .clickable(onClick = onCycleSpeed)
                                            .padding(horizontal = 8.dp)
                                    )
                                    IconButton(
                                        modifier = Modifier.size(34.dp).testTag("speed-up"),
                                        onClick = { onStepSpeed(0.1f) }
                                    ) {
                                        Icon(Icons.Filled.Add, contentDescription = "Increase speed", tint = UnspliceIvory, modifier = Modifier.size(16.dp))
                                    }
                                }
                                UnspliceFilterChip(
                                    selected = sleepLabel != "Off",
                                    label = "Sleep: $sleepLabel",
                                    leadingIcon = Icons.Filled.Bedtime,
                                    onClick = onCycleSleepTimer
                                )
                            }
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                UnspliceFilterChip(
                                    selected = autoSkip,
                                    enabled = canToggleAutoSkip,
                                    label = if (autoSkip) "Auto-skip: ON" else "Auto-skip: OFF",
                                    leadingIcon = Icons.Filled.Shield,
                                    onClick = onToggleAutoSkip
                                )
                                UnspliceFilterChip(
                                    selected = trimSilence,
                                    label = "Trim silence",
                                    leadingIcon = Icons.Filled.GraphicEq,
                                    onClick = onToggleTrimSilence
                                )
                                UnspliceFilterChip(
                                    selected = voiceBoost,
                                    label = "Voice boost",
                                    leadingIcon = Icons.Filled.RecordVoiceOver,
                                    onClick = onToggleVoiceBoost
                                )
                                if (chapters.isNotEmpty()) {
                                    UnspliceFilterChip(
                                        selected = showPlayerChapters,
                                        label = "Chapters (${chapters.size})",
                                        leadingIcon = Icons.AutoMirrored.Filled.List,
                                        onClick = onToggleChapters
                                    )
                                }
                                if (hasTranscript) {
                                    UnspliceFilterChip(
                                        selected = false,
                                        label = "Transcript",
                                        leadingIcon = Icons.Filled.Subtitles,
                                        onClick = onOpenTranscript
                                    )
                                }
                            }
                        }
                    }
                    if (showPlayerChapters && chapters.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        val activeChapter = remember(chapters, position, duration) { currentChapter(chapters, position, duration) }
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = UnspliceSurface,
                            border = BorderStroke(1.dp, UnspliceHairline),
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
                                            style = MaterialTheme.typography.labelSmall.tabular(),
                                            color = if (isActive) UnspliceMint else UnspliceSlate,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.width(52.dp)
                                        )
                                        Text(
                                            ch.title,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isActive) UnspliceIvory else UnspliceIvory.copy(alpha = 0.88f),
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
        if (visible && showPlayerAds) {
            val isScanning = episodeProgress != null || episodeStatus in listOf("QUEUED", "DOWNLOADING", "PROCESSING")
            ModalBottomSheet(
                onDismissRequest = onDismissAdBreaks,
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = UnspliceSurface,
                contentColor = UnspliceIvory
            ) {
                DarkSheetSystemBars()
                Column(
                    Modifier.fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "Ad breaks",
                                style = MaterialTheme.typography.titleLarge,
                                color = UnspliceIvory
                            )
                            Text(
                                if (autoSkip) "Auto-skip enabled for this show" else "Auto-skip disabled • manual skip only",
                                style = MaterialTheme.typography.bodySmall,
                                color = UnspliceSlate
                            )
                        }
                        IconButton(onClick = onDismissAdBreaks) {
                            Icon(Icons.Filled.Close, contentDescription = "Close ad breaks", tint = UnspliceSlate)
                        }
                    }
                    if (!isScanning && episodeStatus != null) {
                        FilledTonalButton(
                            onClick = onScanAds,
                            shape = RoundedCornerShape(50),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = UnspliceSurfaceElevated,
                                contentColor = UnspliceIvory
                            )
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Scan again")
                        }
                    }
                    if (isScanning) {
                        val scanLabel = episodeProgress?.summary() ?: when (episodeStatus) {
                            "PROCESSING" -> "Finding ads…"
                            "DOWNLOADING" -> "Downloading episode…"
                            else -> "Queued for ad scan…"
                        }
                        Text(scanLabel, style = MaterialTheme.typography.bodySmall.tabular(), color = UnspliceMint, fontWeight = FontWeight.Medium)
                        UnspliceProgressBar(fraction = episodeProgress?.percent?.let { it / 100f }, height = 4.dp)
                    } else if (segments.isEmpty()) {
                        Text(
                            "No ad breaks detected yet for this episode.",
                            style = MaterialTheme.typography.bodySmall,
                            color = UnspliceSlate
                        )
                    } else {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = UnspliceSurfaceElevated,
                            border = BorderStroke(1.dp, UnspliceHairline),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                segments.forEachIndexed { idx, seg ->
                                    if (idx > 0) HorizontalDivider(color = UnspliceHairline)
                                    val isCurrentSeg = position >= seg.startMs && position < seg.endMs
                                    val manual = if (seg.confidence < AUTO_SKIP_CONFIDENCE) " • manual skip" else ""
                                    Row(
                                        Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(if (isCurrentSeg) UnspliceAmberContainer else Color.Transparent)
                                            .clickable { onSeek(seg.startMs) }
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f).padding(end = 6.dp)) {
                                            Text(
                                                "${formatTime(seg.startMs)} – ${formatTime(seg.endMs)}",
                                                style = MaterialTheme.typography.bodySmall.tabular(),
                                                color = if (isCurrentSeg) UnspliceAmber else UnspliceIvory,
                                                fontWeight = FontWeight.SemiBold,
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
            }
        }
    }
}
