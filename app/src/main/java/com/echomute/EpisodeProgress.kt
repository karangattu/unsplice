package com.echomute

import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

data class EpisodeProgress(
    val stage: String,
    val percent: Int? = null,
    val remainingMs: Long? = null,
    val detail: String? = null
) {
    fun summary(): String {
        val base = when {
            percent != null && detail != null -> "$stage \u2022 ${percent.coerceIn(0, 100)}% ($detail)"
            percent != null -> "$stage \u2022 ${percent.coerceIn(0, 100)}%"
            detail != null -> "$stage \u2022 $detail"
            else -> stage
        }
        return if (remainingMs != null) "$base \u2022 ${formatRemainingTime(remainingMs)}" else base
    }
}

fun estimateRemainingMs(done: Long, total: Long, elapsedMs: Long): Long? {
    if (total <= 0 || done <= 0 || done >= total || elapsedMs < 500) return null
    return elapsedMs * (total - done) / done
}

fun formatRemainingTime(remainingMs: Long): String {
    val seconds = ((remainingMs.coerceAtLeast(0) + 999) / 1000).coerceAtLeast(1)
    if (seconds < 60) return "~${seconds}s left"
    val min = seconds / 60
    val sec = seconds % 60
    return if (sec == 0L || min >= 10) "~${min}m left" else "~${min}m ${sec}s left"
}

fun formatByteProgress(doneBytes: Long, totalBytes: Long): String? = when {
    totalBytes >= 100_000 -> "%.1f / %.1f MB".format(Locale.US, doneBytes / 1_000_000.0, totalBytes / 1_000_000.0)
    totalBytes <= 0 && doneBytes >= 100_000 -> "%.1f MB".format(Locale.US, doneBytes / 1_000_000.0)
    else -> null
}

fun buildStepProgress(
    stage: String,
    done: Long,
    total: Long,
    elapsedMs: Long,
    includeBytes: Boolean = false
): EpisodeProgress {
    val percent = if (total > 0) ((done * 100) / total).toInt().coerceIn(0, 100) else null
    val remainingMs = estimateRemainingMs(done, total, elapsedMs)
    val detail = if (includeBytes) formatByteProgress(done, total) else null
    return EpisodeProgress(stage, percent, remainingMs, detail)
}

object EpisodeProgressTracker {
    private val state = MutableStateFlow<Map<String, EpisodeProgress>>(emptyMap())
    val progress: StateFlow<Map<String, EpisodeProgress>> = state

    fun observe(episodeId: String): Flow<EpisodeProgress?> = state.map { it[episodeId] }.distinctUntilChanged()

    fun update(episodeId: String, progress: EpisodeProgress) {
        state.update { it + (episodeId to progress) }
    }

    fun clear(episodeId: String) {
        state.update { it - episodeId }
    }
}

class StepProgressReporter(
    private val episodeId: String,
    private val onProgress: (EpisodeProgress) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis
) {
    private var currentStage = ""
    private var stageStartMs = 0L
    private var lastEmitMs = 0L
    private var lastPercent: Int? = null

    @Synchronized
    fun beginStage(stage: String) {
        currentStage = stage
        stageStartMs = clock()
        lastEmitMs = 0L
        lastPercent = null
        emit(EpisodeProgress(stage))
    }

    @Synchronized
    fun report(done: Long, total: Long, includeBytes: Boolean = false) {
        val now = clock()
        val elapsed = (now - stageStartMs).coerceAtLeast(0)
        val progress = buildStepProgress(currentStage, done, total, elapsed, includeBytes)
        val firstDeterminate = lastPercent == null && (progress.percent != null || progress.detail != null)
        val completed = progress.percent == 100 && lastPercent != 100
        val due = now - lastEmitMs >= 250 && (progress.percent != lastPercent || progress.detail != null || progress.remainingMs != null)
        if (firstDeterminate || completed || due) {
            lastEmitMs = now
            lastPercent = progress.percent
            emit(progress)
        }
    }

    private fun emit(progress: EpisodeProgress) {
        EpisodeProgressTracker.update(episodeId, progress)
        onProgress(progress)
    }
}

fun formatCompactDuration(ms: Long): String {
    val seconds = (ms.coerceAtLeast(0) / 1000).coerceAtLeast(1)
    if (seconds < 60) return "${seconds}s"
    val min = seconds / 60
    val sec = seconds % 60
    return if (sec == 0L) "${min}m" else "${min}m ${sec}s"
}

fun formatAdSummary(segments: List<AdSegment>, autoSkip: Boolean = true): String? {
    if (segments.isEmpty()) return null
    val skippedMs = if (autoSkip) segments.filter { it.confidence >= AUTO_SKIP_CONFIDENCE }.sumOf { (it.endMs - it.startMs).coerceAtLeast(0) } else 0L
    val totalMs = segments.sumOf { (it.endMs - it.startMs).coerceAtLeast(0) }
    val countLabel = "${segments.size} ad segment(s) detected"
    return when {
        skippedMs > 0 -> "$countLabel \u2022 ${formatCompactDuration(skippedMs)} skipped"
        totalMs > 0 -> "$countLabel \u2022 ${formatCompactDuration(totalMs)} total"
        else -> countLabel
    }
}

fun filterEpisodes(
    episodes: List<Episode>,
    query: String = "",
    filter: String = "All",
    oldestFirst: Boolean = false
): List<Episode> {
    val ordered = if (oldestFirst) episodes.asReversed() else episodes
    val trimmed = query.trim()
    return ordered.filter { ep ->
        val matchesFilter = when (filter) {
            "Unplayed" -> !ep.finished
            "Downloaded" -> !ep.localPath.isNullOrBlank()
            else -> true
        }
        matchesFilter && (trimmed.isEmpty() || ep.title.contains(trimmed, ignoreCase = true))
    }
}

fun isPodcastSubscribed(podcasts: List<Podcast>, feedUrl: String): Boolean {
    val trimmed = feedUrl.trim()
    val hash = stableHash(trimmed)
    return podcasts.any { it.id == hash || it.feedUrl.equals(trimmed, ignoreCase = true) }
}
