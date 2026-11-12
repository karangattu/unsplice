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
    val durationMs: Long? = null,
    val transcriptUrl: String? = null,
    val transcriptType: String? = null,
    val pscChaptersJson: String? = null
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

private val leadingChapterLine = Regex("""^\s*(?:[-*•]\s*)?[\[(]?((?:\d{1,2}:)?\d{1,3}:\d{2})[\])]?(?!\s*(?:am|pm)\b)\s*[-–—:|]?\s*(.+?)\s*$""", RegexOption.IGNORE_CASE)
private val trailingChapterLine = Regex("""^\s*(?:[-*•]\s*)?(.+?)\s*(?:[-–—|]|[\[(])\s*((?:\d{1,2}:)?\d{1,3}:\d{2})[\])]?\s*$""")

private fun parseChapterTimestampMs(raw: String): Long? {
    val parts = raw.split(':')
    if (parts.size !in 2..3) return null
    val nums = parts.map { it.toLongOrNull() ?: return null }
    val seconds = nums.last()
    if (seconds !in 0..59) return null
    val totalSec = if (nums.size == 2) {
        nums[0] * 60 + seconds
    } else {
        if (nums[1] !in 0..59) return null
        nums[0] * 3600 + nums[1] * 60 + seconds
    }
    return totalSec * 1000L
}

fun extractTimestampedChapters(description: String?, durationMs: Long? = null): List<Chapter> {
    if (description.isNullOrBlank()) return emptyList()
    val entries = mutableListOf<Pair<Long, String>>()
    for (line in description.lineSequence()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val lead = leadingChapterLine.matchEntire(trimmed)
        val (ms, label) = if (lead != null) {
            (parseChapterTimestampMs(lead.groupValues[1]) ?: continue) to lead.groupValues[2].trim()
        } else {
            val trail = trailingChapterLine.matchEntire(trimmed) ?: continue
            (parseChapterTimestampMs(trail.groupValues[2]) ?: continue) to trail.groupValues[1].trim()
        }
        if (label.isEmpty()) continue
        if (entries.isNotEmpty() && ms < entries.last().first) continue
        entries.add(ms to label)
    }
    if (entries.size < 2 || entries.last().first <= entries.first().first) return emptyList()
    return entries.mapIndexed { i, (startMs, label) ->
        val endMs = entries.getOrNull(i + 1)?.first ?: durationMs?.takeIf { it > startMs }
        Chapter(startMs, endMs, label)
    }
}

private fun transcriptPriority(type: String, lang: String): Int {
    val base = when {
        type.contains("json") -> 30
        type.contains("vtt") -> 20
        type.contains("subrip") || type.contains("srt") -> 10
        type.isEmpty() -> 5
        else -> 1
    }
    val langBonus = if (lang.isEmpty() || lang == "en" || lang.startsWith("en-")) 2 else 0
    return base + langBonus
}

fun parseFeed(input: InputStream): Feed {
    val parser = SAXParserFactory.newInstance().newSAXParser().xmlReader
    parser.setFeature("http://xml.org/sax/features/external-general-entities", false)
    parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    var feedTitle = ""; var item = false; var episodeTitle = ""; var guid = ""; var audio = ""; var chapters: String? = null; var published: Long? = null
    var description: String? = null; var durationMs: Long? = null
    var transcriptUrl: String? = null; var transcriptType: String? = null; var transcriptScore = 0
    val pscChapters = mutableListOf<Chapter>()
    var channelImage = false; var rssArtwork: String? = null; var itunesArtwork: String? = null
    val episodes = mutableListOf<FeedEpisode>()
    val text = StringBuilder()
    val handler = object : DefaultHandler() {
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource("".byteInputStream())
        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            text.setLength(0)
            if (qName.equals("item", true)) {
                item = true; episodeTitle = ""; guid = ""; audio = ""; chapters = null; published = null; description = null; durationMs = null
                transcriptUrl = null; transcriptType = null; transcriptScore = 0; pscChapters.clear()
            }
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
            if (item && qName.equals("podcast:transcript", true)) {
                val url = attributes.getValue("url").orEmpty().trim()
                val type = attributes.getValue("type").orEmpty().trim().lowercase()
                val lang = attributes.getValue("language").orEmpty().trim().lowercase()
                if (httpUrl(url)) {
                    val score = transcriptPriority(type, lang)
                    if (score > transcriptScore) {
                        transcriptScore = score
                        transcriptUrl = url
                        transcriptType = type.ifEmpty { null }
                    }
                }
            }
            if (item && qName.equals("psc:chapters", true)) {
                pscChapters.clear()
            }
            if (item && qName.equals("psc:chapter", true)) {
                val startMs = attributes.getValue("start")?.let(::parseNptMs)
                if (startMs != null) {
                    val title = attributes.getValue("title").orEmpty().trim()
                    val href = attributes.getValue("href")?.trim()?.takeIf { it.isNotEmpty() }
                    pscChapters.add(Chapter(startMs, null, title, url = href))
                }
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
                "item" -> {
                    if (audio.isNotEmpty()) {
                        val pscJson = if (pscChapters.isNotEmpty()) encodeChaptersJson(pscChapters.sortedBy { it.startMs }) else null
                        episodes.add(
                            FeedEpisode(
                                guid.ifEmpty { audio },
                                episodeTitle.ifEmpty { "Untitled episode" },
                                audio,
                                chapters,
                                published,
                                description,
                                durationMs,
                                transcriptUrl,
                                transcriptType,
                                pscJson
                            )
                        )
                    }
                    item = false
                }
            }
            text.setLength(0)
        }
    }
    parser.contentHandler = handler; parser.entityResolver = handler
    parser.parse(InputSource(input))
    require(feedTitle.isNotBlank()) { "Feed has no title" }
    return Feed(feedTitle, episodes, itunesArtwork ?: rssArtwork)
}
