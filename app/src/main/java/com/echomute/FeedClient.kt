package com.echomute

import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.URI
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

private val feedClient = OkHttpClient()

/** Full podcast feeds with a long back catalogue and rich show notes are routinely 5 to 20 MB. */
private const val MAX_FEED_BYTES = 64L * 1024 * 1024
private const val MAX_HTML_BYTES = 8L * 1024 * 1024

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

fun fetchFeed(url: String): Feed = fetchFeed(url, MAX_FEED_BYTES)

/** [maxBytes] is a safety net against a runaway response, not a limit on ordinary feeds. */
internal fun fetchFeed(url: String, maxBytes: Long): Feed {
    require(httpUrl(url)) { "Enter a valid feed URL" }
    feedClient.newCall(Request.Builder().url(url).header("User-Agent", "Unsplice/1.0 (Android)").build()).execute().use { response ->
        check(response.isSuccessful) { "Feed request failed: HTTP ${response.code}" }
        val body = requireNotNull(response.body)
        val declared = body.contentLength()
        require(declared < 0 || declared <= maxBytes) { "Feed is too large" }
        val source = body.source()

        // Peek at the start so a pasted website URL can still be auto-discovered, then stream the RSS
        // straight into the SAX parser instead of holding the whole document in a byte array.
        val head = ByteArray(512)
        val headBytes = source.peek().read(head)
        val prefix = String(head, 0, maxOf(headBytes, 0), Charsets.UTF_8).trimStart().lowercase()
        if (prefix.startsWith("<!doctype html") || prefix.startsWith("<html")) {
            require(!source.request(MAX_HTML_BYTES + 1)) { "Page is too large" }
            val html = source.readUtf8()
            val discovered = discoverFeedUrlFromHtml(html, url)
            require(discovered != null && discovered != url) { "No RSS feed found at $url" }
            return fetchFeed(discovered, maxBytes)
        }
        return parseFeed(LimitedInputStream(source.inputStream(), maxBytes))
    }
}

fun fetchJsonChapters(url: String): List<Chapter> = parseJsonChapters(fetchText(url, 1024 * 1024L))

fun fetchTranscript(url: String): String = fetchText(url, 8 * 1024 * 1024L)

fun fetchOmnyAdInfo(clip: OmnyClip): OmnyAdInfo? = parseOmnyClip(fetchText(clip.apiUrl, 1024 * 1024L))

fun fetchOmnyWaveform(url: String): Pair<Int, IntArray> = parseOmnyWaveform(fetchText(url, 8 * 1024 * 1024L))

fun fetchArt19Clip(audioUrl: String): Art19AdInfo? =
    art19ClipUrl(audioUrl)?.let { parseArt19Clip(fetchText(it, 1024 * 1024L)) }

fun fetchArt19Waveform(url: String): Pair<Int, IntArray> =
    parseArt19Waveform(fetchBytes(url, 8 * 1024 * 1024L))

private fun fetchText(url: String, maxBytes: Long): String {
    require(httpUrl(url)) { "Invalid URL" }
    feedClient.newCall(Request.Builder().url(url).header("User-Agent", "Unsplice/1.0 (Android)").build()).execute().use { response ->
        check(response.isSuccessful) { "Request failed: HTTP ${response.code}" }
        val source = requireNotNull(response.body).source()
        require(!source.request(maxBytes + 1)) { "Response is too large" }
        return source.readUtf8()
    }
}

private fun fetchBytes(url: String, maxBytes: Long): ByteArray {
    require(httpUrl(url)) { "Invalid URL" }
    feedClient.newCall(Request.Builder().url(url).header("User-Agent", "Unsplice/1.0 (Android)").build()).execute().use { response ->
        check(response.isSuccessful) { "Request failed: HTTP ${response.code}" }
        val source = requireNotNull(response.body).source()
        require(!source.request(maxBytes + 1)) { "Response is too large" }
        return source.readByteArray()
    }
}

/** Counts bytes during the streaming parse so a chunked response cannot grow without bound. */
private class LimitedInputStream(input: InputStream, private val maxBytes: Long) : FilterInputStream(input) {
    private var readBytes = 0L

    override fun read(): Int {
        val value = super.read()
        if (value >= 0) addBytes(1)
        return value
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val count = super.read(b, off, len)
        if (count > 0) addBytes(count)
        return count
    }

    private fun addBytes(count: Int) {
        readBytes += count
        if (readBytes > maxBytes) throw IOException("Feed is too large")
    }
}
