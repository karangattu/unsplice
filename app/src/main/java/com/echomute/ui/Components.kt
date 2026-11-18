package com.echomute

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.graphics.scale
import androidx.core.view.WindowCompat
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

internal fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return if (min >= 60) "%d:%02d:%02d".format(min / 60, min % 60, sec) else "%02d:%02d".format(min, sec)
}

@Composable
internal fun DarkSheetSystemBars() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        window?.let {
            WindowCompat.getInsetsController(it, it.decorView).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
            if (Build.VERSION.SDK_INT >= 29) it.isNavigationBarContrastEnforced = false
        }
    }
}

@Composable
internal fun UnspliceFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    enabled: Boolean = true
) {
    FilterChip(
        selected = selected,
        enabled = enabled,
        onClick = onClick,
        shape = RoundedCornerShape(50),
        leadingIcon = leadingIcon?.let {
            { Icon(it, contentDescription = null, modifier = Modifier.size(16.dp)) }
        },
        label = {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                maxLines = 1,
                softWrap = false
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = UnspliceSurfaceElevated,
            labelColor = UnspliceSlate,
            iconColor = UnspliceSlate,
            selectedContainerColor = UnspliceMintContainer,
            selectedLabelColor = UnspliceMint,
            selectedLeadingIconColor = UnspliceMint
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = enabled,
            selected = selected,
            borderColor = UnspliceHairline,
            selectedBorderColor = UnspliceMint.copy(alpha = 0.40f),
            borderWidth = 1.dp,
            selectedBorderWidth = 1.dp
        ),
        modifier = modifier
    )
}

@Composable
internal fun UnspliceProgressBar(
    fraction: Float?,
    modifier: Modifier = Modifier,
    height: Dp = 3.dp,
    color: Color = UnspliceMint,
    trackColor: Color = UnspliceTrack
) {
    val barModifier = modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(50))
    if (fraction != null) {
        val clamped = fraction.coerceIn(0f, 1f)
        LinearProgressIndicator(
            progress = { clamped },
            modifier = barModifier,
            color = color,
            trackColor = trackColor
        )
    } else {
        LinearProgressIndicator(
            modifier = barModifier,
            color = color,
            trackColor = trackColor
        )
    }
}

@Composable
internal fun PlaybackProgress(
    position: Long,
    duration: Long,
    segments: List<AdSegment>,
    chapters: List<Chapter> = emptyList(),
    autoSkip: Boolean = true,
    onSeek: (Long) -> Unit
) {
    if (duration <= 0) return
    var scrubPosition by remember(duration) { mutableStateOf<Long?>(null) }
    val shownPosition = scrubPosition ?: position
    val fraction = (shownPosition.toFloat() / duration).coerceIn(0f, 1f)
    val activeChapter = remember(chapters, shownPosition, duration) { currentChapter(chapters, shownPosition, duration) }
    Column {
        if (activeChapter != null && activeChapter.title.isNotBlank()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 2.dp)
            ) {
                Box(
                    Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(UnspliceMint)
                )
                Text(
                    activeChapter.title,
                    style = MaterialTheme.typography.labelMedium,
                    color = UnspliceMint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("current-chapter-title")
                )
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(6.dp).clearAndSetSemantics { }) {
                val y = size.height / 2
                drawLine(UnspliceTrack, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
                if (fraction > 0) drawLine(UnspliceMint, Offset(0f, y), Offset(size.width * fraction, y), size.height, StrokeCap.Round)
                val minWidthPx = 3.dp.toPx()
                segments.forEach { ad ->
                    val start = (ad.startMs.toFloat() / duration).coerceIn(0f, 1f) * size.width
                    val rawEnd = (ad.endMs.toFloat() / duration).coerceIn(0f, 1f) * size.width
                    val end = maxOf(rawEnd, (start + minWidthPx).coerceAtMost(size.width))
                    val color = if (autoSkip && ad.confidence >= AUTO_SKIP_CONFIDENCE) UnspliceAmber else UnspliceAmber.copy(alpha = 0.55f)
                    if (end > start) drawLine(color, Offset(start, y), Offset(end, y), size.height, StrokeCap.Round)
                }
                chapters.forEach { chapter ->
                    if (chapter.startMs in 1 until duration) {
                        val x = (chapter.startMs.toFloat() / duration).coerceIn(0f, 1f) * size.width
                        drawLine(UnspliceIvory.copy(alpha = 0.72f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
                    }
                }
            }
            Slider(
                value = shownPosition.toFloat().coerceIn(0f, duration.toFloat()),
                onValueChange = { scrubPosition = it.toLong() },
                onValueChangeFinished = {
                    scrubPosition?.let(onSeek)
                    scrubPosition = null
                },
                valueRange = 0f..duration.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = UnspliceIvory,
                    activeTrackColor = Color.Transparent,
                    inactiveTrackColor = Color.Transparent
                ),
                modifier = Modifier.fillMaxWidth().height(44.dp).semantics { contentDescription = "Playback position" }
            )
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                formatTime(shownPosition),
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                formatTime(duration),
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun MiniPlaybackProgress(position: Long, duration: Long, segments: List<AdSegment>, autoSkip: Boolean = true) {
    if (duration <= 0) return
    val fraction = (position.toFloat() / duration).coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50)).clearAndSetSemantics { }) {
        val y = size.height / 2
        drawLine(UnspliceTrack, Offset(0f, y), Offset(size.width, y), size.height, StrokeCap.Round)
        if (fraction > 0) drawLine(UnspliceMint, Offset(0f, y), Offset(size.width * fraction, y), size.height, StrokeCap.Round)
        val minWidthPx = 2.dp.toPx()
        segments.forEach { ad ->
            val start = (ad.startMs.toFloat() / duration).coerceIn(0f, 1f) * size.width
            val rawEnd = (ad.endMs.toFloat() / duration).coerceIn(0f, 1f) * size.width
            val end = maxOf(rawEnd, (start + minWidthPx).coerceAtMost(size.width))
            val color = if (autoSkip && ad.confidence >= AUTO_SKIP_CONFIDENCE) UnspliceAmber else UnspliceAmber.copy(alpha = 0.55f)
            if (end > start) drawLine(color, Offset(start, y), Offset(end, y), size.height, StrokeCap.Round)
        }
    }
}

@Composable
internal fun PlayingEqualizer(playing: Boolean, modifier: Modifier = Modifier) {
    val bars = listOf(
        remember { Animatable(0f) },
        remember { Animatable(0f) },
        remember { Animatable(0f) }
    )
    LaunchedEffect(playing) {
        if (!playing) {
            bars.forEach { bar -> launch { bar.animateTo(0f, tween(180)) } }
            return@LaunchedEffect
        }
        val motions = listOf(
            Triple(0.25f, 1f, 440),
            Triple(0.9f, 0.32f, 620),
            Triple(0.4f, 0.85f, 520)
        )
        bars.forEachIndexed { index, bar ->
            launch {
                val (low, high, cycleMs) = motions[index]
                var next = high
                while (isActive) {
                    bar.animateTo(next, tween(cycleMs / 2, easing = FastOutSlowInEasing))
                    next = if (next == high) low else high
                }
            }
        }
    }
    Row(
        modifier = modifier.height(18.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        bars.forEachIndexed { index, bar ->
            Box(
                Modifier
                    .width(3.dp)
                    .height(14.dp * bar.value.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(50))
                    .background(if (playing) UnspliceMint else UnspliceSlate)
                    .testTag("equalizer-bar-$index")
            )
        }
    }
}

@Composable
internal fun SettingValueRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Surface(
            shape = RoundedCornerShape(50),
            color = UnspliceSurfaceBright,
            border = BorderStroke(1.dp, UnspliceHairline)
        ) {
            Text(
                value,
                color = UnspliceMint,
                style = MaterialTheme.typography.labelMedium.tabular(),
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
            )
        }
    }
}

@Composable
internal fun SeekButton(label: String, mirrored: Boolean, contentDescription: String, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(56.dp).semantics { this.contentDescription = contentDescription }
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                Icons.Filled.Replay,
                contentDescription = null,
                tint = UnspliceIvory,
                modifier = Modifier.size(36.dp).graphicsLayer { scaleX = if (mirrored) -1f else 1f }
            )
            Text(
                label,
                style = MaterialTheme.typography.labelSmall.tabular(),
                color = UnspliceIvory,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

private val artworkClient = OkHttpClient()
private val artworkCache = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.byteCount
}
private val auraColorCache = LruCache<String, Int>(256)

private fun computeAuraFromBitmap(url: String, bitmap: Bitmap): Int? {
    auraColorCache.get(url)?.let { return it }
    val scaled = bitmap.scale(16, 16)
    val pixels = IntArray(16 * 16)
    scaled.getPixels(pixels, 0, 16, 0, 0, 16, 16)
    if (scaled !== bitmap) scaled.recycle()
    val aura = extractArtworkAuraArgb(pixels)
    if (aura != null) auraColorCache.put(url, aura)
    return aura
}

private suspend fun loadArtwork(url: String, pixels: Int): Bitmap? {
    if (!httpUrl(url)) return null
    val key = "$url@$pixels"
    artworkCache.get(key)?.let { cached ->
        if (auraColorCache.get(url) == null) computeAuraFromBitmap(url, cached)
        return cached
    }
    val request = runCatching { Request.Builder().url(url).build() }.getOrNull() ?: return null
    return suspendCancellableCoroutine { continuation ->
        val call = artworkClient.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resume(null)
            }

            override fun onResponse(call: Call, response: Response) {
                val bitmap = try {
                    response.use {
                        val source = it.body?.source()
                        if (!it.isSuccessful || source == null || source.request(5 * 1024 * 1024L + 1)) null
                        else {
                            val bytes = source.readByteArray()
                            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                            if (options.outWidth <= 0 || options.outHeight <= 0) null else {
                                var sample = 1
                                while (maxOf(options.outWidth, options.outHeight) / sample > pixels * 2 && sample < (1 shl 29)) sample *= 2
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                            }
                        }
                    }
                } catch (_: Exception) { null }
                if (bitmap != null) {
                    artworkCache.put(key, bitmap)
                    computeAuraFromBitmap(url, bitmap)
                }
                if (continuation.isActive) continuation.resume(bitmap)
            }
        })
    }
}

@Composable
internal fun rememberPodcastAuraColor(title: String, url: String?): Color {
    val fallback = remember(title) { Color(fallbackArtworkAuraArgb(title)) }
    val cachedAura = url?.let { auraColorCache.get(it) }
    val resolvedArgb by produceState<Int?>(initialValue = cachedAura, url) {
        if (url == null) {
            value = null
            return@produceState
        }
        auraColorCache.get(url)?.let {
            value = it
            return@produceState
        }
        loadArtwork(url, 128)
        value = auraColorCache.get(url)
    }
    val target = resolvedArgb?.let(::Color) ?: fallback
    val animated by animateColorAsState(targetValue = target, animationSpec = tween(420), label = "artworkAura")
    return animated
}

private fun artworkGradient(seed: String): Brush {
    val (start, end) = ArtworkPalette[artworkPaletteIndex(seed)]
    return Brush.linearGradient(listOf(Color(start), Color(end)))
}

@Composable
internal fun PodcastArtwork(title: String, url: String?, size: Dp = 44.dp, cornerRadius: Dp = 10.dp) {
    val pixels = (size.value * LocalContext.current.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
    val bitmap by produceState<Bitmap?>(initialValue = null, url, pixels) {
        value = null
        value = url?.let { loadArtwork(it, pixels) }
    }
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = Modifier
            .size(size)
            .clip(shape)
            .background(artworkGradient(title), shape)
            .border(1.dp, UnspliceHairline, shape),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap == null) {
            Text(
                title.take(2).uppercase(),
                style = if (size >= 80.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = UnspliceIvory
            )
        } else {
            Image(
                bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().clip(shape).testTag("podcast-artwork-image")
            )
        }
    }
}

@Composable
internal fun UnspliceSearchField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Search,
    onSubmit: () -> Unit = {}
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = UnspliceSlate) },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear", tint = UnspliceSlate, modifier = Modifier.size(18.dp))
                }
            }
        } else null,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }, onDone = { onSubmit() }),
        shape = RoundedCornerShape(16.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = UnspliceMint,
            focusedLabelColor = UnspliceMint,
            unfocusedBorderColor = UnspliceOutline,
            unfocusedLabelColor = UnspliceSlate,
            focusedContainerColor = UnspliceSurface,
            unfocusedContainerColor = UnspliceSurface
        ),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
internal fun EmptyStateCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = UnspliceSurface,
        border = BorderStroke(1.dp, UnspliceHairline)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 30.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(
                        Brush.radialGradient(
                            listOf(UnspliceMint.copy(alpha = 0.28f), UnspliceAmber.copy(alpha = 0.10f), Color.Transparent)
                        ),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(UnspliceMintContainer, CircleShape)
                        .border(1.dp, UnspliceMint.copy(alpha = 0.25f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = UnspliceMint, modifier = Modifier.size(24.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 11.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = UnspliceMint, contentColor = UnspliceOnMint)
                ) {
                    Text(actionLabel, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable
internal fun StatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    borderColor: Color = Color.Transparent,
    icon: ImageVector? = null
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = containerColor,
        border = if (borderColor != Color.Transparent) BorderStroke(1.dp, borderColor) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(13.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}
