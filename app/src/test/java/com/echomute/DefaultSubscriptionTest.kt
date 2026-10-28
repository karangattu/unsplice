package com.echomute

import android.content.SharedPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class DefaultSubscriptionTest {

    private class FakeDao : LibraryDao {
        val podcasts = mutableMapOf<String, Podcast>()
        val episodes = mutableMapOf<String, Episode>()

        override suspend fun savePodcast(podcast: Podcast) { podcasts[podcast.id] = podcast }
        override suspend fun setArtworkIfMissing(id: String, url: String) {
            podcasts[id]?.takeIf { it.artworkUrl == null }?.let { podcasts[id] = it.copy(artworkUrl = url) }
        }
        override suspend fun saveEpisode(episode: Episode) { episodes[episode.id] = episode }
        override suspend fun saveSegments(segments: List<AdSegment>) {}
        override fun observePodcasts(): Flow<List<Podcast>> = flowOf(podcasts.values.toList())
        override suspend fun podcast(id: String): Podcast? = podcasts[id]
        override fun observeEpisodes(id: String): Flow<List<Episode>> = flowOf(episodes.values.filter { it.podcastId == id })
        override suspend fun episodesForPodcast(id: String): List<Episode> = episodes.values.filter { it.podcastId == id }
        override suspend fun episode(id: String): Episode? = episodes[id]
        override suspend fun segments(id: String): List<AdSegment> = emptyList()
        override fun observeAutoSkip(episodeId: String): Flow<Boolean?> = flowOf(null)
        override fun observeSegments(id: String): Flow<List<AdSegment>> = flowOf(emptyList())
        override suspend fun deleteSegments(id: String) {}
        override suspend fun deleteSegment(id: Long) {}
        override suspend fun deletePodcast(id: String) { podcasts.remove(id) }
        override suspend fun deleteEpisode(id: String) { episodes.remove(id) }
    }

    private class FakeEditor(private val data: MutableMap<String, Any?>) : SharedPreferences.Editor {
        private val temp = mutableMapOf<String, Any?>()
        override fun putString(key: String, value: String?) = apply { temp[key] = value }
        override fun putStringSet(key: String, values: Set<String>?) = apply { temp[key] = values }
        override fun putInt(key: String, value: Int) = apply { temp[key] = value }
        override fun putLong(key: String, value: Long) = apply { temp[key] = value }
        override fun putFloat(key: String, value: Float) = apply { temp[key] = value }
        override fun putBoolean(key: String, value: Boolean) = apply { temp[key] = value }
        override fun remove(key: String) = apply { temp.remove(key) }
        override fun clear() = apply { temp.clear() }
        override fun commit(): Boolean { data.putAll(temp); return true }
        override fun apply() { data.putAll(temp) }
    }

    private class FakePrefs : SharedPreferences {
        val map = mutableMapOf<String, Any?>()
        override fun getAll(): Map<String, *> = map
        override fun getString(key: String, defValue: String?): String? = map[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String, defValues: Set<String>?): Set<String>? = map[key] as? Set<String> ?: defValues
        override fun getInt(key: String, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String): Boolean = key in map
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    @Test
    fun savesArtworkOnSubscriptionAndPreservesExistingState() = runBlocking {
        val dao = FakeDao()
        val url = "https://example.org/feed"
        val id = stableHash(url)
        dao.savePodcast(Podcast(id, "Old title", url, autoSkip = false))
        val episodeId = stableHash("$id\u0000episode")
        dao.saveEpisode(Episode(episodeId, id, "Old episode", "https://example.org/old.mp3", localPath = "/download.mp3", status = "READY"))
        DefaultSubscription.subscribeFeed(url, dao) {
            Feed("Show", listOf(FeedEpisode("episode", "Episode", "https://example.org/new.mp3")), "https://example.org/cover.jpg")
        }
        assertEquals("https://example.org/cover.jpg", dao.podcast(id)?.artworkUrl)
        assertEquals(false, dao.podcast(id)?.autoSkip)
        assertEquals("/download.mp3", dao.episode(episodeId)?.localPath)
    }

    @Test
    fun backfillsExistingArtworkAndRetriesFailuresWithoutRepeatedEmptyResults() = runBlocking {
        val dao = FakeDao()
        val prefs = FakePrefs()
        dao.savePodcast(Podcast("has", "Has", "https://example.org/has", artworkUrl = "https://example.org/existing.jpg"))
        dao.savePodcast(Podcast("found", "Found", "https://example.org/found", autoSkip = false))
        dao.savePodcast(Podcast("empty", "Empty", "https://example.org/empty"))
        dao.savePodcast(Podcast("failed", "Failed", "https://example.org/failed"))
        val calls = mutableListOf<String>()
        suspend fun fetch(url: String): Feed {
            calls += url
            if (url.endsWith("failed")) throw java.io.IOException("offline")
            return Feed("Show", emptyList(), if (url.endsWith("found")) "https://example.org/found.jpg" else null)
        }
        DefaultSubscription.refreshMissingArtwork(prefs, dao, now = 1000L, fetcher = ::fetch)
        assertEquals("https://example.org/found.jpg", dao.podcast("found")?.artworkUrl)
        assertEquals(false, dao.podcast("found")?.autoSkip)
        assertEquals("https://example.org/existing.jpg", dao.podcast("has")?.artworkUrl)
        calls.clear()
        DefaultSubscription.refreshMissingArtwork(prefs, dao, now = 2000L, fetcher = ::fetch)
        assertEquals(listOf("https://example.org/failed"), calls)
    }

    @Test
    fun subscribesTheNewsJunkieByDefault() = runBlocking {
        val dao = FakeDao()
        val prefs = FakePrefs()

        val fakeFeed = Feed(
            title = "The News Junkie",
            episodes = listOf(
                FeedEpisode("ep1", "Episode 1", "https://audio.example.com/1.mp3"),
                FeedEpisode("ep2", "Episode 2", "https://audio.example.com/2.mp3")
            )
        )

        DefaultSubscription.ensureDefaultSubscribed(prefs, dao) { fakeFeed }

        val podcastId = stableHash(DefaultSubscription.NEWS_JUNKIE_FEED_URL)
        val podcast = dao.podcast(podcastId)
        assertNotNull(podcast)
        assertEquals("The News Junkie", podcast?.title)
        assertEquals(2, dao.episodesForPodcast(podcastId).size)
        assertTrue(prefs.getBoolean("news_junkie_subscribed", false))
    }

    @Test
    fun handlesOfflineNetworkGracefully() = runBlocking {
        val dao = FakeDao()
        val prefs = FakePrefs()

        DefaultSubscription.ensureDefaultSubscribed(prefs, dao) {
            throw java.io.IOException("Network unreachable")
        }

        val podcastId = stableHash(DefaultSubscription.NEWS_JUNKIE_FEED_URL)
        val podcast = dao.podcast(podcastId)
        assertNotNull(podcast)
        assertEquals("The News Junkie", podcast?.title)
        assertEquals(0, dao.episodesForPodcast(podcastId).size)
        assertFalse(prefs.getBoolean("news_junkie_subscribed", false))
    }
}
