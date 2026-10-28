package com.echomute

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest

internal fun stableHash(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

object DefaultSubscription {
    const val NEWS_JUNKIE_FEED_URL =
        "https://www.omnycontent.com/d/playlist/98a2599f-cd9b-4f2a-9be4-b22c00e585f2/cf386c8a-37eb-415e-abd5-b32f0124a981/5183d8e7-a5ca-40e2-9641-b32f0124a98b/podcast.rss"
    const val NEWS_JUNKIE_TITLE = "The News Junkie"

    suspend fun subscribeFeed(
        url: String,
        dao: LibraryDao,
        fetcher: suspend (String) -> Feed = { fetchFeed(it) }
    ): String = withContext(Dispatchers.IO) {
        val trimmed = url.trim()
        val feed = fetcher(trimmed)
        val podcastId = stableHash(trimmed)
        val old = dao.podcast(podcastId)
        dao.savePodcast(Podcast(podcastId, feed.title.ifEmpty { NEWS_JUNKIE_TITLE }, trimmed, old?.autoSkip ?: true))
        for (item in feed.episodes) {
            val id = stableHash("$podcastId\u0000${item.id}")
            val existing = dao.episode(id)
            dao.saveEpisode(
                existing?.copy(title = item.title, audioUrl = item.audioUrl, chaptersUrl = item.chaptersUrl, publishedAt = item.publishedAt)
                    ?: Episode(id, podcastId, item.title, item.audioUrl, chaptersUrl = item.chaptersUrl, publishedAt = item.publishedAt)
            )
        }
        podcastId
    }

    suspend fun ensureDefaultSubscribed(
        context: Context,
        dao: LibraryDao,
        fetcher: suspend (String) -> Feed = { fetchFeed(it) }
    ) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("app_defaults", Context.MODE_PRIVATE)
        ensureDefaultSubscribed(prefs, dao, fetcher)
    }

    suspend fun ensureDefaultSubscribed(
        prefs: SharedPreferences,
        dao: LibraryDao,
        fetcher: suspend (String) -> Feed = { fetchFeed(it) }
    ) = withContext(Dispatchers.IO) {
        if (prefs.getBoolean("news_junkie_subscribed", false)) return@withContext
        val podcastId = stableHash(NEWS_JUNKIE_FEED_URL)
        if (dao.podcast(podcastId) == null) {
            dao.savePodcast(Podcast(podcastId, NEWS_JUNKIE_TITLE, NEWS_JUNKIE_FEED_URL, true))
        }
        try {
            subscribeFeed(NEWS_JUNKIE_FEED_URL, dao, fetcher)
            prefs.edit().putBoolean("news_junkie_subscribed", true).apply()
        } catch (_: Exception) {
        }
    }
}
