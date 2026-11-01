package com.echomute

import java.io.InputStream
import java.net.URI
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

data class FeedEpisode(
    val id: String,
    val title: String,
    val audioUrl: String,
    val chaptersUrl: String? = null,
    val publishedAt: Long? = null,
    val description: String? = null,
    val durationMs: Long? = null
)
data class Feed(val title: String, val episodes: List<FeedEpisode>, val artworkUrl: String? = null)

fun httpUrl(value: String): Boolean = try {
    val uri = URI(value)
    (uri.scheme == "https" || uri.scheme == "http") && !uri.host.isNullOrEmpty() && uri.userInfo == null
} catch (_: Exception) { false }

/** Parses an RSS pubDate such as "Tue, 27 Oct 2026 19:42:17 +0000" to epoch milliseconds. */
fun parsePubDate(value: String): Long? = try {
    ZonedDateTime.parse(value.trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli()
} catch (_: Exception) { null }

fun parseItunesDuration(value: String): Long? {
    val parts = value.trim().split(':')
    if (parts.isEmpty() || parts.size > 3) return null
    val nums = parts.map { it.toLongOrNull() ?: return null }
    val seconds = when (nums.size) {
        1 -> nums[0]
        2 -> nums[0] * 60 + nums[1]
        else -> nums[0] * 3600 + nums[1] * 60 + nums[2]
    }
    return if (seconds > 0) seconds * 1000L else null
}

fun stripHtml(raw: String): String {
    if (raw.isBlank()) return ""
    val out = StringBuilder(raw.length)
    var inTag = false
    var i = 0
    while (i < raw.length) {
        val ch = raw[i]
        if (ch == '<') {
            val rest = raw.regionMatches(i + 1, "br", 0, 2, ignoreCase = true) ||
                raw.regionMatches(i + 1, "/p", 0, 2, ignoreCase = true) ||
                raw.regionMatches(i + 1, "/li", 0, 3, ignoreCase = true)
            if (rest && out.isNotEmpty() && out.last() != '\n') out.append('\n')
            inTag = true
        } else if (ch == '>') {
            inTag = false
        } else if (!inTag) {
            out.append(ch)
        }
        i++
    }
    return out.toString()
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("[ \\t]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .trim()
}

fun parseFeed(input: InputStream): Feed {
    val parser = SAXParserFactory.newInstance().newSAXParser().xmlReader
    parser.setFeature("http://xml.org/sax/features/external-general-entities", false)
    parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    var feedTitle = ""; var item = false; var episodeTitle = ""; var guid = ""; var audio = ""; var chapters: String? = null; var published: Long? = null
    var description: String? = null; var durationMs: Long? = null
    var channelImage = false; var rssArtwork: String? = null; var itunesArtwork: String? = null
    val episodes = mutableListOf<FeedEpisode>()
    val text = StringBuilder()
    val handler = object : DefaultHandler() {
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource("".byteInputStream())
        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            text.setLength(0)
            if (qName.equals("item", true)) { item = true; episodeTitle = ""; guid = ""; audio = ""; chapters = null; published = null; description = null; durationMs = null }
            if (!item && qName.equals("image", true)) channelImage = true
            if (!item && qName.equals("itunes:image", true)) {
                val url = attributes.getValue("href").orEmpty().trim()
                if (httpUrl(url)) itunesArtwork = url
            }
            if (item && qName.equals("enclosure", true)) {
                val url = attributes.getValue("url").orEmpty().trim()
                val type = attributes.getValue("type").orEmpty()
                if (httpUrl(url) && (type.startsWith("audio/") || type.isBlank())) audio = url
            }
            if (item && qName.equals("podcast:chapters", true)) {
                val url = attributes.getValue("url").orEmpty().trim()
                if (httpUrl(url)) chapters = url
            }
        }
        override fun characters(ch: CharArray, start: Int, length: Int) { text.append(ch, start, length) }
        override fun endElement(uri: String?, localName: String?, qName: String) {
            val value = text.toString().trim()
            when (qName.lowercase()) {
                "title" -> if (item) episodeTitle = value else if (feedTitle.isEmpty()) feedTitle = value
                "guid" -> if (item) guid = value
                "pubdate" -> if (item) published = parsePubDate(value)
                "description", "itunes:summary", "content:encoded" -> if (item && value.isNotEmpty()) {
                    val cleaned = stripHtml(value)
                    if (cleaned.isNotEmpty() && (description == null || qName.equals("content:encoded", true))) description = cleaned
                }
                "itunes:duration" -> if (item) durationMs = parseItunesDuration(value)
                "url" -> if (!item && channelImage && httpUrl(value)) rssArtwork = value
                "image" -> if (!item) channelImage = false
                "item" -> { if (audio.isNotEmpty()) episodes.add(FeedEpisode(guid.ifEmpty { audio }, episodeTitle.ifEmpty { "Untitled episode" }, audio, chapters, published, description, durationMs)); item = false }
            }
            text.setLength(0)
        }
    }
    parser.contentHandler = handler; parser.entityResolver = handler
    parser.parse(InputSource(input))
    require(feedTitle.isNotBlank()) { "Feed has no title" }
    return Feed(feedTitle, episodes, itunesArtwork ?: rssArtwork)
}
