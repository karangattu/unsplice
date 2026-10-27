package com.echomute

import java.net.URLEncoder
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class PodcastSearchResult(
    val title: String,
    val artist: String,
    val feedUrl: String,
    val artworkUrl: String? = null
)

private val searchClient = OkHttpClient()

fun parseSearchResults(jsonString: String): List<PodcastSearchResult> {
    val json = JSONObject(jsonString)
    val results = json.optJSONArray("results") ?: return emptyList()
    val list = ArrayList<PodcastSearchResult>(results.length())
    for (i in 0 until results.length()) {
        val item = results.optJSONObject(i) ?: continue
        val feedUrl = item.optString("feedUrl").trim()
        if (feedUrl.isEmpty() || !httpUrl(feedUrl)) continue
        val title = item.optString("collectionName").ifEmpty { item.optString("trackName") }.trim()
        if (title.isEmpty()) continue
        val artist = item.optString("artistName").trim()
        val artwork = item.optString("artworkUrl100").takeIf { it.isNotBlank() && httpUrl(it) }
        list.add(PodcastSearchResult(title, artist, feedUrl, artwork))
    }
    return list
}

fun searchPodcasts(query: String, client: OkHttpClient = searchClient): List<PodcastSearchResult> {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return emptyList()
    val encoded = URLEncoder.encode(trimmed, "UTF-8")
    val url = "https://itunes.apple.com/search?media=podcast&term=$encoded&limit=25"
    val request = Request.Builder()
        .url(url)
        .header("User-Agent", "Unsplice/1.0 (Android)")
        .build()
    client.newCall(request).execute().use { response ->
        check(response.isSuccessful) { "Search failed: HTTP ${response.code}" }
        val body = response.body?.string().orEmpty()
        return parseSearchResults(body)
    }
}
