package com.echomute

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import javax.xml.parsers.SAXParserFactory
import org.json.JSONArray
import org.json.JSONObject
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler

data class Chapter(val startMs: Long, val endMs: Long?, val title: String, val toc: Boolean = true, val url: String? = null)

data class Id3Metadata(val chapters: List<Chapter>, val adBreakCuesMs: List<Long> = emptyList())

private val sponsorTitle = Regex(
    """\b(ads?|ad break|advert\w*|sponsors?\w*|commercials?|promos?|(pre|mid|post)[- ]?rolls?|paid (content|promotion|partnership)|brought to you by|support(ed)? (for|by) (this|the|today's) (show|podcast|episode)|(word|message) from our (sponsors?|partners?))\b""",
    RegexOption.IGNORE_CASE
)

private val sponsorUrlPattern = Regex(
    """(?:utm_|[?&](?:ref|promo|offer|discount|sponsor|partner|aff)=|/(?:podcast|pod|show|sponsor|promo)\b|sponsor)""",
    RegexOption.IGNORE_CASE
)

private val aisAdBreakPattern = Regex("""AIS_AD_BREAK_[A-Z0-9_]+\s*=\s*([^\s;]+)""", RegexOption.IGNORE_CASE)

fun isSponsorUrl(url: String?): Boolean = !url.isNullOrBlank() && sponsorUrlPattern.containsMatchIn(url)

fun isSponsorChapter(chapter: Chapter): Boolean =
    sponsorTitle.containsMatchIn(chapter.title) || (!chapter.toc && isSponsorUrl(chapter.url))

fun sponsorConfidence(chapter: Chapter, baseConfidence: Float): Float =
    if (!chapter.toc && isSponsorChapter(chapter)) maxOf(baseConfidence, 0.9f) else baseConfidence

fun hasTrustedJsonChapters(actualDurationMs: Long?, rssDurationMs: Long?): Boolean {
    if (actualDurationMs == null || rssDurationMs == null || actualDurationMs <= 0L || rssDurationMs <= 0L) return false
    return kotlin.math.abs(actualDurationMs - rssDurationMs) <= 5_000L
}

/** Chapters whose titles mark them as ads or sponsor reads. An open-ended chapter runs to the next one or to [durationMs]. */
fun sponsorSegments(chapters: List<Chapter>, durationMs: Long?): List<Segment> {
    val sorted = chapters.sortedBy { it.startMs }
    return sorted.mapIndexedNotNull { i, chapter ->
        if (!isSponsorChapter(chapter)) return@mapIndexedNotNull null
        val end = chapter.endMs ?: sorted.getOrNull(i + 1)?.startMs ?: durationMs ?: return@mapIndexedNotNull null
        if (end > chapter.startMs) Segment(chapter.startMs, end) else null
    }
}

/** Parses a Podcasting 2.0 JSON chapters file (times in seconds). */
fun parseJsonChapters(json: String): List<Chapter> {
    val array = JSONObject(json).optJSONArray("chapters") ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val chapter = array.optJSONObject(i) ?: return@mapNotNull null
        if (!chapter.has("startTime")) return@mapNotNull null
        val end = if (chapter.has("endTime")) (chapter.getDouble("endTime") * 1000).toLong() else null
        val toc = chapter.optBoolean("toc", true)
        val url = chapter.optString("url").takeIf { it.isNotBlank() }
        Chapter((chapter.getDouble("startTime") * 1000).toLong(), end, chapter.optString("title"), toc, url)
    }
}

fun encodeChaptersJson(chapters: List<Chapter>): String {
    val array = JSONArray()
    for (c in chapters) {
        val obj = JSONObject()
        obj.put("startTime", c.startMs / 1000.0)
        if (c.endMs != null) obj.put("endTime", c.endMs / 1000.0)
        obj.put("title", c.title)
        if (!c.toc) obj.put("toc", false)
        if (c.url != null) obj.put("url", c.url)
        array.put(obj)
    }
    return JSONObject().put("version", "1.2.0").put("chapters", array).toString()
}

fun parseNptMs(value: String): Long? {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split(':')
    if (parts.size > 3) return null
    return try {
        when (parts.size) {
            1 -> (parts[0].toDouble() * 1000.0).toLong()
            2 -> ((parts[0].toLong() * 60 + parts[1].toDouble()) * 1000.0).toLong()
            else -> ((parts[0].toLong() * 3600 + parts[1].toLong() * 60 + parts[2].toDouble()) * 1000.0).toLong()
        }
    } catch (_: Exception) {
        null
    }
}

fun parsePodloveChapters(xml: String): List<Chapter> {
    if (xml.isBlank()) return emptyList()
    val parser = SAXParserFactory.newInstance().newSAXParser().xmlReader
    parser.setFeature("http://xml.org/sax/features/external-general-entities", false)
    parser.setFeature("http://xml.org/sax/features/external-parameter-entities", false)
    val chapters = mutableListOf<Chapter>()
    val handler = object : DefaultHandler() {
        override fun resolveEntity(publicId: String?, systemId: String?): InputSource = InputSource("".byteInputStream())
        override fun startElement(uri: String?, localName: String?, qName: String, attributes: Attributes) {
            if (qName.equals("psc:chapter", true) || qName.equals("chapter", true)) {
                val startMs = attributes.getValue("start")?.let(::parseNptMs) ?: return
                val title = attributes.getValue("title").orEmpty().trim()
                val href = attributes.getValue("href")?.trim()?.takeIf { it.isNotEmpty() }
                chapters.add(Chapter(startMs, null, title, url = href))
            }
        }
    }
    parser.contentHandler = handler
    parser.entityResolver = handler
    parser.parse(InputSource(xml.byteInputStream()))
    return chapters.sortedBy { it.startMs }
}

/** Reads ID3v2.3/2.4 CHAP frames from the start of an MP3. Returns an empty list if the file has none. */
fun readId3Chapters(input: InputStream): List<Chapter> = readId3Metadata(input).chapters

fun readId3Metadata(input: InputStream): Id3Metadata {
    val data = DataInputStream(input)
    val header = ByteArray(10)
    try { data.readFully(header) } catch (_: EOFException) { return Id3Metadata(emptyList()) }
    val version = header[3].toInt()
    if (String(header, 0, 3, Charsets.ISO_8859_1) != "ID3" || version !in 3..4) return Id3Metadata(emptyList())
    val flags = header[5].toInt()
    if (flags and 0x80 != 0) return Id3Metadata(emptyList())
    val size = syncsafe(header, 6)
    if (size > 64 * 1024 * 1024) return Id3Metadata(emptyList())
    val tag = ByteArray(size)
    try { data.readFully(tag) } catch (_: EOFException) { return Id3Metadata(emptyList()) }
    var start = 0
    if (flags and 0x40 != 0) start = if (version == 4) syncsafe(tag, 0) else int32(tag, 0) + 4
    val allFrames = frames(tag, start, tag.size, version)
    val chapters = allFrames.filter { it.first == "CHAP" }.mapNotNull { (_, from, to) ->
        var pos = from
        while (pos < to && tag[pos] != 0.toByte()) pos++
        pos++
        if (pos + 16 > to) return@mapNotNull null
        val startMs = int32(tag, pos).toLong() and 0xFFFFFFFFL
        val endMs = int32(tag, pos + 4).toLong() and 0xFFFFFFFFL
        val title = frames(tag, pos + 16, to, version).firstOrNull { it.first == "TIT2" }?.let { (_, a, b) -> text(tag, a, b) }.orEmpty()
        Chapter(startMs, endMs, title)
    }
    val cues = mutableListOf<Long>()
    for ((id, from, to) in allFrames) {
        if (id == "TXXX") {
            val (desc, value) = userText(tag, from, to)
            parseAisAdBreakCues("$desc=$value", cues)
            parseAisAdBreakCues(desc, cues)
            parseAisAdBreakCues(value, cues)
        } else if (id == "COMM" && from + 4 < to) {
            val (_, body) = userText(tag, from, to, prefixBytes = 3)
            parseAisAdBreakCues(body, cues)
        }
    }
    return Id3Metadata(chapters, cues.distinct().sorted())
}

private fun parseAisAdBreakCues(text: String, out: MutableList<Long>) {
    if (!text.contains("AIS_AD_BREAK", ignoreCase = true)) return
    for (match in aisAdBreakPattern.findAll(text)) {
        val ms = parseAisAdBreakTimeMs(match.groupValues[1])
        if (ms != null) out.add(ms)
    }
}

private fun parseAisAdBreakTimeMs(raw: String): Long? {
    val head = raw.substringBefore(',').trim()
    head.toLongOrNull()?.let { return it }
    val parts = head.split(':')
    if (parts.size == 4) {
        val h = parts[0].toLongOrNull() ?: return null
        val m = parts[1].toLongOrNull() ?: return null
        val s = parts[2].toLongOrNull() ?: return null
        val ms = parts[3].toLongOrNull() ?: return null
        return ((h * 3600 + m * 60 + s) * 1000L) + ms
    }
    return parseNptMs(head)
}

private fun frames(tag: ByteArray, from: Int, to: Int, version: Int): List<Triple<String, Int, Int>> {
    val result = mutableListOf<Triple<String, Int, Int>>()
    var pos = from
    while (pos + 10 <= to && tag[pos] != 0.toByte()) {
        val id = String(tag, pos, 4, Charsets.ISO_8859_1)
        val size = if (version == 4) syncsafe(tag, pos + 4) else int32(tag, pos + 4)
        if (size < 0 || pos + 10 + size > to) break
        result.add(Triple(id, pos + 10, pos + 10 + size))
        pos += 10 + size
    }
    return result
}

private fun text(tag: ByteArray, from: Int, to: Int): String {
    if (from >= to) return ""
    val charset = when (tag[from].toInt()) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
    return String(tag, from + 1, to - from - 1, charset).trim('\u0000', ' ')
}

private fun userText(tag: ByteArray, from: Int, to: Int, prefixBytes: Int = 0): Pair<String, String> {
    if (from + 1 + prefixBytes >= to) return "" to ""
    val encoding = tag[from].toInt()
    val charset = when (encoding) { 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; 3 -> Charsets.UTF_8; else -> Charsets.ISO_8859_1 }
    val start = from + 1 + prefixBytes
    val wide = encoding == 1 || encoding == 2
    var split = start
    if (wide) {
        while (split + 1 < to && !(tag[split] == 0.toByte() && tag[split + 1] == 0.toByte())) split += 2
        val desc = String(tag, start, (split - start).coerceAtLeast(0), charset).trim('\u0000', ' ')
        val valStart = (split + 2).coerceAtMost(to)
        val value = String(tag, valStart, to - valStart, charset).trim('\u0000', ' ')
        return desc to value
    }
    while (split < to && tag[split] != 0.toByte()) split++
    val desc = String(tag, start, split - start, charset).trim('\u0000', ' ')
    val valStart = (split + 1).coerceAtMost(to)
    val value = String(tag, valStart, to - valStart, charset).trim('\u0000', ' ')
    return desc to value
}

private fun int32(b: ByteArray, at: Int) =
    (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)

private fun syncsafe(b: ByteArray, at: Int) =
    (b[at].toInt() and 0x7F shl 21) or (b[at + 1].toInt() and 0x7F shl 14) or (b[at + 2].toInt() and 0x7F shl 7) or (b[at + 3].toInt() and 0x7F)

fun writeChapters(file: java.io.File, chapters: List<Chapter>) {
    file.bufferedWriter().use { out ->
        for (c in chapters.sortedBy { it.startMs }) {
            out.write("${c.startMs}\t${c.endMs ?: -1L}\t${c.title.replace('\t', ' ').replace('\n', ' ')}\n")
        }
    }
}

fun readChapters(file: java.io.File): List<Chapter> {
    if (!file.isFile) return emptyList()
    return file.useLines { lines ->
        lines.mapNotNull { line ->
            val parts = line.split('\t', limit = 3)
            if (parts.size < 3) return@mapNotNull null
            val start = parts[0].toLongOrNull() ?: return@mapNotNull null
            val endRaw = parts[1].toLongOrNull() ?: -1L
            Chapter(start, endRaw.takeIf { it >= 0L }, parts[2])
        }.toList()
    }
}

fun currentChapter(chapters: List<Chapter>, positionMs: Long, durationMs: Long?): Chapter? {
    if (chapters.isEmpty()) return null
    val sorted = chapters.sortedBy { it.startMs }
    for (i in sorted.indices.reversed()) {
        val c = sorted[i]
        if (positionMs >= c.startMs) {
            val end = c.endMs ?: sorted.getOrNull(i + 1)?.startMs ?: durationMs ?: Long.MAX_VALUE
            if (positionMs < end) return c
        }
    }
    return null
}
