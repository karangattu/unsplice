package com.echomute

import android.content.SharedPreferences
import java.io.File
import kotlin.math.roundToInt
import kotlin.math.roundToLong

data class TimestampLink(val label: String, val positionMs: Long)

private val TIMESTAMP_REGEX = Regex("""\b(?:(\d{1,2}):)?(\d{1,2}):(\d{2})\b""")

fun extractTimestamps(text: String): List<TimestampLink> {
    if (text.isBlank()) return emptyList()
    return TIMESTAMP_REGEX.findAll(text).mapNotNull { match ->
        val hours = match.groups[1]?.value?.toLongOrNull() ?: 0L
        val minutes = match.groups[2]?.value?.toLongOrNull() ?: return@mapNotNull null
        val seconds = match.groups[3]?.value?.toLongOrNull() ?: return@mapNotNull null
        if (minutes >= 60 || seconds >= 60) return@mapNotNull null
        val ms = (hours * 3600 + minutes * 60 + seconds) * 1000L
        TimestampLink(match.value, ms)
    }.distinctBy { it.positionMs }.toList()
}

data class TranscriptLine(val startMs: Long, val endMs: Long, val text: String)

fun groupTranscriptLines(words: List<TranscriptWord>, windowMs: Long = 8_000L): List<TranscriptLine> {
    if (words.isEmpty()) return emptyList()
    val result = ArrayList<TranscriptLine>()
    var lineStart = words[0].startMs
    var lineEnd = words[0].endMs
    val builder = StringBuilder(words[0].text.trim())
    for (i in 1 until words.size) {
        val word = words[i]
        val text = word.text.trim()
        if (text.isEmpty()) continue
        if (word.startMs - lineStart >= windowMs || word.startMs - lineEnd > 2_500L) {
            result.add(TranscriptLine(lineStart, lineEnd, builder.toString()))
            lineStart = word.startMs
            lineEnd = word.endMs
            builder.setLength(0)
            builder.append(text)
        } else {
            if (builder.isNotEmpty() && !text.startsWith(",") && !text.startsWith(".") && !text.startsWith("'") && !text.startsWith("?") && !text.startsWith("!")) {
                builder.append(' ')
            }
            builder.append(text)
            lineEnd = maxOf(lineEnd, word.endMs)
        }
    }
    if (builder.isNotEmpty()) result.add(TranscriptLine(lineStart, lineEnd, builder.toString()))
    return result
}

fun filterTranscriptLines(lines: List<TranscriptLine>, query: String): List<TranscriptLine> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return lines
    return lines.filter { it.text.contains(trimmed, ignoreCase = true) }
}

fun filterSmartEpisodes(episodes: List<Episode>, filter: String, query: String = ""): List<Episode> {
    val trimmedQuery = query.trim()
    return episodes.filter { ep ->
        val matchesFilter = when (filter) {
            "In Progress" -> ep.positionMs > 0L && !ep.finished
            "Downloaded" -> ep.localPath != null && ep.status == "READY"
            "Starred" -> ep.starred
            else -> !ep.finished
        }
        matchesFilter && (trimmedQuery.isEmpty() || ep.title.contains(trimmedQuery, ignoreCase = true))
    }
}

fun unplayedCountsByPodcast(episodes: List<Episode>): Map<String, Int> {
    val counts = HashMap<String, Int>()
    for (ep in episodes) {
        if (!ep.finished) counts[ep.podcastId] = (counts[ep.podcastId] ?: 0) + 1
    }
    return counts
}

data class ListeningStats(
    val totalListenedMs: Long,
    val adSkippedMs: Long,
    val speedSavedMs: Long,
    val silenceSavedMs: Long
) {
    val totalSavedMs: Long get() = adSkippedMs + speedSavedMs + silenceSavedMs
}

object ListeningStatsStore {
    fun read(prefs: SharedPreferences): ListeningStats = ListeningStats(
        totalListenedMs = prefs.getLong("totalListenedMs", 0L),
        adSkippedMs = prefs.getLong("adSkippedMs", 0L),
        speedSavedMs = prefs.getLong("speedSavedMs", 0L),
        silenceSavedMs = prefs.getLong("silenceSavedMs", 0L)
    )

    fun recordAdSkipped(prefs: SharedPreferences, durationMs: Long) {
        if (durationMs <= 0L) return
        val current = prefs.getLong("adSkippedMs", 0L)
        prefs.edit().putLong("adSkippedMs", current + durationMs).apply()
    }

    fun recordPlaybackTick(
        prefs: SharedPreferences,
        wallElapsedMs: Long,
        speed: Float,
        trimSilence: Boolean
    ) {
        if (wallElapsedMs <= 0L) return
        val contentMs = (wallElapsedMs * speed.coerceAtLeast(0.5f)).roundToLong()
        val speedSaved = (contentMs - wallElapsedMs).coerceAtLeast(0L)
        val silenceSaved = if (trimSilence) (wallElapsedMs * 0.06f).roundToLong().coerceAtLeast(1L) else 0L
        prefs.edit()
            .putLong("totalListenedMs", prefs.getLong("totalListenedMs", 0L) + contentMs)
            .putLong("speedSavedMs", prefs.getLong("speedSavedMs", 0L) + speedSaved)
            .putLong("silenceSavedMs", prefs.getLong("silenceSavedMs", 0L) + silenceSaved)
            .apply()
    }
}

fun stepSpeed(current: Float, delta: Float): Float {
    val stepped = ((current + delta) * 10f).roundToInt() / 10f
    return stepped.coerceIn(0.5f, 3.0f)
}

data class PodcastStorageUsage(
    val podcastId: String,
    val title: String,
    val episodeCount: Int,
    val bytes: Long
)

fun calculatePodcastStorage(
    filesDir: File,
    podcasts: List<Podcast>,
    episodes: List<Episode>
): List<PodcastStorageUsage> {
    val titles = podcasts.associate { it.id to it.title }
    val bytesByPodcast = HashMap<String, Long>()
    val countByPodcast = HashMap<String, Int>()
    for (ep in episodes) {
        val dir = File(filesDir, "episodes/${ep.id}")
        val dirBytes = if (dir.isDirectory) {
            dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            val file = ep.localPath?.let(::File)
            if (file != null && file.isFile) file.length() else 0L
        }
        if (dirBytes > 0L) {
            bytesByPodcast[ep.podcastId] = (bytesByPodcast[ep.podcastId] ?: 0L) + dirBytes
            countByPodcast[ep.podcastId] = (countByPodcast[ep.podcastId] ?: 0) + 1
        }
    }
    return bytesByPodcast.entries
        .map { (id, bytes) ->
            PodcastStorageUsage(
                podcastId = id,
                title = titles[id] ?: "Unknown podcast",
                episodeCount = countByPodcast[id] ?: 0,
                bytes = bytes
            )
        }
        .sortedByDescending { it.bytes }
}

fun formatMegabytes(bytes: Long): String = "%.1f".format(java.util.Locale.US, bytes / 1_000_000.0)
