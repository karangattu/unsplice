package com.echomute

import java.net.URI
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

    val candidates = mutableListOf<String>()
    candidates.add(trimmed)

    if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
        try {
            val uri = URI(trimmed)
            val host = uri.host?.removePrefix("www.")?.removeSuffix(".com")?.removeSuffix(".org")?.removeSuffix(".net")
            if (!host.isNullOrBlank()) {
                candidates.add(host)
                candidates.add(host.replace(Regex("([a-z])([A-Z])"), "$1 $2"))
            }
        } catch (_: Exception) {}
    } else if (trimmed.startsWith("the ", ignoreCase = true)) {
        candidates.add(trimmed.substring(4).trim())
    }

    val resultsMap = LinkedHashMap<String, PodcastSearchResult>()

    for (term in candidates) {
        val encoded = URLEncoder.encode(term, "UTF-8")
        val url = "https://itunes.apple.com/search?media=podcast&term=$encoded&limit=50"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Unsplice/1.0 (Android)")
            .build()
        try {
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    for (item in parseSearchResults(body)) {
                        if (!resultsMap.containsKey(item.feedUrl)) {
                            resultsMap[item.feedUrl] = item
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    if (resultsMap.isEmpty() && (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true))) {
        try {
            val feed = fetchFeed(trimmed)
            resultsMap[trimmed] = PodcastSearchResult(feed.title, "", trimmed)
        } catch (_: Exception) {}
    }

    val clean = trimmed.removePrefix("https://").removePrefix("http://").removePrefix("www.").trim().trimEnd('/')
    val withoutThe = if (trimmed.startsWith("the ", ignoreCase = true)) trimmed.substring(4).trim() else trimmed
    return resultsMap.values.sortedWith { a, b ->
        val aExact = a.title.equals(trimmed, ignoreCase = true) || a.title.equals(withoutThe, ignoreCase = true) || (clean.isNotEmpty() && a.title.replace(" ", "").contains(clean, ignoreCase = true))
        val bExact = b.title.equals(trimmed, ignoreCase = true) || b.title.equals(withoutThe, ignoreCase = true) || (clean.isNotEmpty() && b.title.replace(" ", "").contains(clean, ignoreCase = true))
        when {
            aExact && !bExact -> -1
            !aExact && bExact -> 1
            else -> 0
        }
    }
}
