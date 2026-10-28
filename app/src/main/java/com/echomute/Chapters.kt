package com.echomute

import java.io.DataInputStream
import java.io.EOFException
import java.io.InputStream
import org.json.JSONObject

data class Chapter(val startMs: Long, val endMs: Long?, val title: String)

private val sponsorTitle = Regex("""\b(ads?|ad break|advert\w*|sponsors?\w*|commercials?|paid (content|promotion|partnership)|brought to you by)\b""", RegexOption.IGNORE_CASE)

/** Chapters whose titles mark them as ads or sponsor reads. An open-ended chapter runs to the next one or to [durationMs]. */
fun sponsorSegments(chapters: List<Chapter>, durationMs: Long?): List<Segment> {
    val sorted = chapters.sortedBy { it.startMs }
    return sorted.mapIndexedNotNull { i, chapter ->
        if (!sponsorTitle.containsMatchIn(chapter.title)) return@mapIndexedNotNull null
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
        Chapter((chapter.getDouble("startTime") * 1000).toLong(), end, chapter.optString("title"))
    }
}

/** Reads ID3v2.3/2.4 CHAP frames from the start of an MP3. Returns an empty list if the file has none. */
fun readId3Chapters(input: InputStream): List<Chapter> {
    val data = DataInputStream(input)
    val header = ByteArray(10)
    try { data.readFully(header) } catch (_: EOFException) { return emptyList() }
    val version = header[3].toInt()
    if (String(header, 0, 3, Charsets.ISO_8859_1) != "ID3" || version !in 3..4) return emptyList()
    val flags = header[5].toInt()
    if (flags and 0x80 != 0) return emptyList() // Whole-tag unsynchronisation is rare in podcasts; not supported.
    val size = syncsafe(header, 6)
    if (size > 64 * 1024 * 1024) return emptyList()
    val tag = ByteArray(size)
    try { data.readFully(tag) } catch (_: EOFException) { return emptyList() }
    var start = 0
    if (flags and 0x40 != 0) start = if (version == 4) syncsafe(tag, 0) else int32(tag, 0) + 4
    return frames(tag, start, tag.size, version).filter { it.first == "CHAP" }.mapNotNull { (_, from, to) ->
        var pos = from
        while (pos < to && tag[pos] != 0.toByte()) pos++ // Element ID
        pos++
        if (pos + 16 > to) return@mapNotNull null
        val startMs = int32(tag, pos).toLong() and 0xFFFFFFFFL
        val endMs = int32(tag, pos + 4).toLong() and 0xFFFFFFFFL
        val title = frames(tag, pos + 16, to, version).firstOrNull { it.first == "TIT2" }?.let { (_, a, b) -> text(tag, a, b) }.orEmpty()
        Chapter(startMs, endMs, title)
    }
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

private fun int32(b: ByteArray, at: Int) =
    (b[at].toInt() and 0xFF shl 24) or (b[at + 1].toInt() and 0xFF shl 16) or (b[at + 2].toInt() and 0xFF shl 8) or (b[at + 3].toInt() and 0xFF)

private fun syncsafe(b: ByteArray, at: Int) =
    (b[at].toInt() and 0x7F shl 21) or (b[at + 1].toInt() and 0x7F shl 14) or (b[at + 2].toInt() and 0x7F shl 7) or (b[at + 3].toInt() and 0x7F)
