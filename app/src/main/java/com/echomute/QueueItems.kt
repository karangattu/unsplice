package com.echomute

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import java.time.Instant
import java.time.ZoneId

/** The publish date travels with the media item as a release date, so Up Next can show it without a database lookup. */
fun QueueItem.toMediaItem(): MediaItem {
    val metadata = MediaMetadata.Builder().setTitle(title).setArtist(artist)
    publishedAt?.let { ms ->
        val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
        metadata.setReleaseYear(date.year).setReleaseMonth(date.monthValue).setReleaseDay(date.dayOfMonth)
    }
    return MediaItem.Builder().setMediaId(id).setUri(uri).setMediaMetadata(metadata.build()).build()
}

fun MediaItem.toQueueItem(): QueueItem {
    val meta = mediaMetadata
    val published = if (meta.releaseYear != null && meta.releaseMonth != null && meta.releaseDay != null) {
        runCatching { java.time.LocalDate.of(meta.releaseYear!!, meta.releaseMonth!!, meta.releaseDay!!).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull()
    } else null
    return QueueItem(mediaId, localConfiguration?.uri?.toString().orEmpty(), meta.title?.toString().orEmpty(), meta.artist?.toString().orEmpty(), published)
}
