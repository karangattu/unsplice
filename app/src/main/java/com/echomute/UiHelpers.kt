package com.echomute

import kotlin.math.abs
import kotlin.math.roundToInt

/** Playful gradient pairs for shows without artwork. Colours stay dark enough for ivory initials. */
internal val ArtworkPalette = listOf(
    0xFF1D6E60L to 0xFF123A4BL,
    0xFF7A4A18L to 0xFF3A2360L,
    0xFF3B3E8FL to 0xFF142A52L,
    0xFF8A2F52L to 0xFF3A1D4AL,
    0xFF2F6E86L to 0xFF16324FL,
    0xFF4A6B2AL to 0xFF123C3AL
)

/** Stable per-show colour: the same title always gets the same gradient. */
internal fun artworkPaletteIndex(seed: String): Int = Math.floorMod(seed.hashCode(), ArtworkPalette.size)

internal fun fallbackArtworkAuraArgb(seed: String): Int {
    val base = ArtworkPalette[artworkPaletteIndex(seed)].first.toInt()
    return harmonizeAuraArgb(base)
}

internal fun extractArtworkAuraArgb(pixels: IntArray): Int? {
    if (pixels.isEmpty()) return null
    var sumR = 0.0
    var sumG = 0.0
    var sumB = 0.0
    var totalWeight = 0.0
    val hsl = FloatArray(3)
    for (argb in pixels) {
        val alpha = (argb ushr 24) and 0xFF
        if (alpha < 128) continue
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        rgbToHsl(r, g, b, hsl)
        val sat = hsl[1]
        val light = hsl[2]
        if (sat < 0.14f || light < 0.08f || light > 0.92f) continue
        val weight = sat.toDouble() * (1.0 - abs(light - 0.5) * 1.4).coerceAtLeast(0.15)
        sumR += r * weight
        sumG += g * weight
        sumB += b * weight
        totalWeight += weight
    }
    if (totalWeight <= 0.0) return null
    val avgR = (sumR / totalWeight).roundToInt().coerceIn(0, 255)
    val avgG = (sumG / totalWeight).roundToInt().coerceIn(0, 255)
    val avgB = (sumB / totalWeight).roundToInt().coerceIn(0, 255)
    return harmonizeAuraArgb((0xFF shl 24) or (avgR shl 16) or (avgG shl 8) or avgB)
}

internal fun harmonizeAuraArgb(argb: Int): Int {
    val hsl = FloatArray(3)
    rgbToHsl((argb ushr 16) and 0xFF, (argb ushr 8) and 0xFF, argb and 0xFF, hsl)
    hsl[1] = hsl[1].coerceIn(0.32f, 0.64f)
    hsl[2] = hsl[2].coerceIn(0.20f, 0.30f)
    return hslToRgb(hsl[0], hsl[1], hsl[2])
}

private fun rgbToHsl(r: Int, g: Int, b: Int, out: FloatArray) {
    val rf = r / 255f
    val gf = g / 255f
    val bf = b / 255f
    val max = maxOf(rf, gf, bf)
    val min = minOf(rf, gf, bf)
    val l = (max + min) * 0.5f
    if (max == min) {
        out[0] = 0f
        out[1] = 0f
        out[2] = l
        return
    }
    val d = max - min
    val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
    var h = when (max) {
        rf -> (gf - bf) / d + (if (gf < bf) 6f else 0f)
        gf -> (bf - rf) / d + 2f
        else -> (rf - gf) / d + 4f
    }
    h /= 6f
    out[0] = h
    out[1] = s
    out[2] = l
}

private fun hslToRgb(h: Float, s: Float, l: Float): Int {
    if (s == 0f) {
        val v = (l * 255f).roundToInt().coerceIn(0, 255)
        return (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }
    val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
    val p = 2f * l - q
    val r = (hueToChannel(p, q, h + 1f / 3f) * 255f).roundToInt().coerceIn(0, 255)
    val g = (hueToChannel(p, q, h) * 255f).roundToInt().coerceIn(0, 255)
    val b = (hueToChannel(p, q, h - 1f / 3f) * 255f).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
}

private fun hueToChannel(p: Float, q: Float, tInput: Float): Float {
    var t = tInput
    if (t < 0f) t += 1f
    if (t > 1f) t -= 1f
    return when {
        t < 1f / 6f -> p + (q - p) * 6f * t
        t < 0.5f -> q
        t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
        else -> p
    }
}

data class SleepTimerUpdate(
    val untilEpochMs: Long,
    val endOfEpisode: Boolean,
    val label: String
)

fun nextSpeedPreset(current: Float): Float = when (current) {
    1f -> 1.25f
    1.25f -> 1.5f
    1.5f -> 2f
    else -> 1f
}

fun nextCycleInt(current: Int, options: List<Int>): Int {
    val idx = options.indexOf(current)
    return if (idx < 0 || idx == options.lastIndex) options.first() else options[idx + 1]
}

const val AUTO_DOWNLOAD_LIMIT_KEY = "autoDownloadLimit"

fun nextAutoDownloadLimit(current: Int): Int = when (current) {
    0 -> 1
    1 -> 2
    2 -> 5
    5 -> 10
    else -> 0
}

fun nextCustomSpeed(current: Float?): Float? = when (current) {
    null -> 1.2f
    1.2f -> 1.5f
    1.5f -> 2.0f
    else -> null
}

fun nextSleepTimerUpdate(
    nowMs: Long,
    currentUntilMs: Long,
    currentEndOfEpisode: Boolean
): SleepTimerUpdate {
    val remainingMin = if (currentUntilMs > nowMs) (currentUntilMs - nowMs) / 60_000L else 0L
    return when {
        !currentEndOfEpisode && remainingMin <= 0L -> SleepTimerUpdate(nowMs + 15 * 60_000L, false, "15m")
        remainingMin in 1..15 -> SleepTimerUpdate(nowMs + 30 * 60_000L, false, "30m")
        remainingMin in 16..30 -> SleepTimerUpdate(nowMs + 60 * 60_000L, false, "60m")
        remainingMin > 30 -> SleepTimerUpdate(0L, true, "End of ep")
        else -> SleepTimerUpdate(0L, false, "Off")
    }
}

fun formatLibrarySummary(podcastCount: Int, unplayedCount: Int): String {
    val showsLabel = if (podcastCount == 1) "1 show" else "$podcastCount shows"
    return if (unplayedCount > 0) "$showsLabel \u2022 $unplayedCount unplayed" else showsLabel
}

fun formatShowEpisodeCount(totalCount: Int, unplayedCount: Int): String {
    val epLabel = if (totalCount == 1) "1 episode" else "$totalCount episodes"
    return if (unplayedCount > 0) "$epLabel \u2022 $unplayedCount unplayed" else "$epLabel \u2022 All played"
}

fun formatSleepLabel(sleepUntilMs: Long, endOfEpisode: Boolean, nowMs: Long = System.currentTimeMillis()): String =
    when {
        endOfEpisode -> "End of ep"
        sleepUntilMs > nowMs -> "${((sleepUntilMs - nowMs + 59_999L) / 60_000L)}m"
        else -> "Off"
    }

fun formatQueueSummary(upcomingCount: Int): String = when (upcomingCount) {
    0 -> "Your listening queue"
    1 -> "1 episode in queue"
    else -> "$upcomingCount episodes in queue"
}

fun queueSelectionMove(currentIndex: Int, targetIndex: Int): Pair<Int, Int>? =
    if (currentIndex >= 0 && targetIndex > currentIndex) targetIndex to currentIndex else null

/**
 * Insertion point for an episode that is not yet in the player queue.
 * Placing it at the current index makes the new episode play now and pushes the
 * previously current episode (and everything after it) down into Up Next.
 */
fun newEpisodeInsertIndex(currentIndex: Int, mediaItemCount: Int): Int =
    if (currentIndex in 0 until mediaItemCount) currentIndex else mediaItemCount

/** Insertion point for "play next": right after the current track, or at the end if nothing is playing. */
fun playNextInsertIndex(currentIndex: Int, mediaItemCount: Int): Int =
    if (currentIndex in 0 until mediaItemCount) currentIndex + 1 else mediaItemCount

fun formatAdSegmentLabel(source: String): String = when (source) {
    "marker" -> "Ad break"
    "chapter" -> "Chapter"
    "transcript" -> "Sponsor read"
    "splice" -> "Audio splice"
    "acoustic" -> "Audio break"
    else -> "Repeat"
}

fun formatAdRemainingLabel(ad: AdSegment, positionMs: Long): String {
    val remainingMs = (ad.endMs - positionMs).coerceAtLeast(0L)
    return if (remainingMs > 0L) "Ad break \u2022 ${formatCompactDuration(remainingMs)} left" else "Ad break detected"
}
