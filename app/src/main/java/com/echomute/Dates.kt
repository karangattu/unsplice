package com.echomute

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val publishedFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

/** A publish date for display, such as "27 Oct 2026". Omits the year when it is the current one. */
fun formatPublished(epochMs: Long, zone: ZoneId = ZoneId.systemDefault(), today: LocalDate = LocalDate.now(zone)): String {
    val date = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    return when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date.year == today.year -> date.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
        else -> date.format(publishedFormat)
    }
}

/** Progress for an episode row: "Played", "12 min left", or null when it has not been started. */
fun progressLabel(positionMs: Long, durationMs: Long?, finished: Boolean): String? = when {
    finished -> "Played"
    positionMs <= 0 -> null
    durationMs == null || durationMs <= positionMs -> "In progress"
    else -> {
        val minutes = ((durationMs - positionMs) / 60_000).toInt()
        if (minutes < 1) "Less than a minute left" else "$minutes min left"
    }
}
