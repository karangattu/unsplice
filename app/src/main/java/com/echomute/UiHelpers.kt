package com.echomute

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

fun formatQueueSummary(upcomingCount: Int): String = when (upcomingCount) {
    0 -> "Your listening queue"
    1 -> "1 episode in queue"
    else -> "$upcomingCount episodes in queue"
}

fun queueSelectionMove(currentIndex: Int, targetIndex: Int): Pair<Int, Int>? =
    if (currentIndex >= 0 && targetIndex > currentIndex) targetIndex to currentIndex else null

fun formatAdSegmentLabel(source: String): String = when (source) {
    "marker" -> "Ad break"
    "chapter" -> "Chapter"
    "transcript" -> "Sponsor read"
    else -> "Repeat"
}

fun formatAdRemainingLabel(ad: AdSegment, positionMs: Long): String {
    val remainingMs = (ad.endMs - positionMs).coerceAtLeast(0L)
    return if (remainingMs > 0L) "Ad break \u2022 ${formatCompactDuration(remainingMs)} left" else "Ad break detected"
}
