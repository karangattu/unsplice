package com.echomute

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.math.roundToInt
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
    val adSummary = remember(segments, autoSkip) { formatAdSummary(segments, autoSkip) }
    Column {
        if (activeChapter != null && activeChapter.title.isNotBlank()) {
            Text(
                activeChapter.title,
                style = MaterialTheme.typography.labelMedium,
                color = UnspliceMint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(bottom = 2.dp).testTag("current-chapter-title")
            )
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxWidth().padding(horizontal = 10.dp).height(6.dp)) {
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
                        drawLine(Color.White.copy(alpha = 0.75f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f)
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
            Text(formatTime(shownPosition), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatTime(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (adSummary != null) {
            Spacer(Modifier.height(6.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = UnspliceAmberContainer,
                    border = BorderStroke(1.dp, UnspliceAmber.copy(alpha = 0.35f))
                ) {
                    Text(
                        adSummary,
                        style = MaterialTheme.typography.labelSmall,
                        color = UnspliceAmber,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }
        }
    }
}

@Composable
internal fun MiniPlaybackProgress(position: Long, duration: Long, segments: List<AdSegment>, autoSkip: Boolean = true) {
    if (duration <= 0) return
    val fraction = (position.toFloat() / duration).coerceIn(0f, 1f)
    Canvas(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(50))) {
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
internal fun SettingValueRow(label: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(end = 8.dp))
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = UnspliceMintContainer,
            border = BorderStroke(1.dp, UnspliceMint.copy(alpha = 0.35f))
        ) {
            Text(
                value,
                color = UnspliceMint,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
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
                style = MaterialTheme.typography.labelSmall,
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

private suspend fun loadArtwork(url: String, pixels: Int): Bitmap? {
    if (!httpUrl(url)) return null
    val key = "$url@$pixels"
    artworkCache.get(key)?.let { return it }
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
                if (bitmap != null) artworkCache.put(key, bitmap)
                if (continuation.isActive) continuation.resume(bitmap)
            }
        })
    }
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
            .background(Brush.linearGradient(listOf(Color(0xFF173D38), Color(0xFF182640))), shape)
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap == null) {
            Text(
                title.take(2).uppercase(),
                style = if (size >= 80.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = UnspliceMint
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
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
        trailingIcon = if (value.isNotEmpty()) {
            {
                IconButton(onClick = { onValueChange("") }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear", modifier = Modifier.size(18.dp))
                }
            }
        } else null,
        keyboardOptions = KeyboardOptions(imeAction = imeAction),
        keyboardActions = KeyboardActions(onSearch = { onSubmit() }, onDone = { onSubmit() }),
        shape = RoundedCornerShape(14.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = UnspliceMint,
            focusedLabelColor = UnspliceMint,
            unfocusedBorderColor = UnspliceOutline,
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
        shape = RoundedCornerShape(18.dp),
        color = UnspliceSurface,
        border = BorderStroke(1.dp, UnspliceOutline)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .background(UnspliceMintContainer, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = UnspliceMint, modifier = Modifier.size(26.dp))
            }
            Spacer(Modifier.height(14.dp))
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = onAction,
                    shape = RoundedCornerShape(24.dp),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = UnspliceMint, contentColor = UnspliceOnMint)
                ) {
                    Text(actionLabel, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
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
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
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
