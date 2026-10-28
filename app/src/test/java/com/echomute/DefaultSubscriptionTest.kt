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
