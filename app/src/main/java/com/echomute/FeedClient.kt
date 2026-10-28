package com.echomute

import java.net.URI
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private val feedClient = OkHttpClient()

fun discoverFeedUrlFromHtml(html: String, baseUrl: String): String? {
    val rssLinkRegex = Regex("""href=["'](https?://[^"']+(?:rss|\.xml|podcast\.rss)[^"']*)["']""", RegexOption.IGNORE_CASE)
    rssLinkRegex.find(html)?.groupValues?.get(1)?.let { return it }

    val linkAlternateRegex = Regex("""<link[^>]+type=["']application/rss\+xml["'][^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
    linkAlternateRegex.find(html)?.groupValues?.get(1)?.let {
        return if (it.startsWith("http")) it else resolveRelativeUrl(baseUrl, it)
    }

    val appleRegex = Regex("""podcasts\.apple\.com/[^/]+/podcast/[^/]+/id(\d+)""", RegexOption.IGNORE_CASE)
    appleRegex.find(html)?.groupValues?.get(1)?.let { id ->
        try {
            val req = Request.Builder().url("https://itunes.apple.com/lookup?id=$id").header("User-Agent", "Unsplice/1.0 (Android)").build()
            feedClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = JSONObject(resp.body?.string().orEmpty())
                    val first = json.optJSONArray("results")?.optJSONObject(0)
                    val feed = first?.optString("feedUrl").orEmpty()
                    if (feed.isNotBlank() && httpUrl(feed)) return feed
                }
            }
        } catch (_: Exception) {}
    }
    return null
}

private fun resolveRelativeUrl(base: String, relative: String): String {
    return try {
        URI(base).resolve(relative).toString()
    } catch (_: Exception) { relative }
}

fun fetchFeed(url: String): Feed {
    require(httpUrl(url)) { "Enter a valid feed URL" }
    feedClient.newCall(Request.Builder().url(url).header("User-Agent", "Unsplice/1.0 (Android)").build()).execute().use { response ->
        check(response.isSuccessful) { "Feed request failed: HTTP ${response.code}" }
        val source = requireNotNull(response.body).source()
        require(!source.request(5 * 1024 * 1024L + 1)) { "Feed is too large" }
        val bytes = source.readByteArray()
        val header = String(bytes, 0, minOf(bytes.size, 512)).trimStart().lowercase()
        if (header.startsWith("<!doctype html") || header.startsWith("<html")) {
            val html = String(bytes, Charsets.UTF_8)
            val discovered = discoverFeedUrlFromHtml(html, url)
            if (discovered != null && discovered != url) {
                return fetchFeed(discovered)
            }
        }
        return parseFeed(bytes.inputStream())
    }
}

fun fetchJsonChapters(url: String): List<Chapter> {
    require(httpUrl(url)) { "Invalid chapters URL" }
    feedClient.newCall(Request.Builder().url(url).header("User-Agent", "Unsplice/1.0 (Android)").build()).execute().use { response ->
        check(response.isSuccessful) { "Chapters request failed: HTTP ${response.code}" }
        val source = requireNotNull(response.body).source()
        require(!source.request(1024 * 1024L + 1)) { "Chapters file is too large" }
        return parseJsonChapters(source.readUtf8())
    }
}
