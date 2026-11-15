package com.echomute

import kotlin.math.abs
import org.json.JSONObject

/**
 * ART19 stitches ads into an otherwise ad-free episode at download time. Its RSS feed carries no
 * transcript, chapters or ad markers; the break slots live in a separate episode JSON document.
 * The ads actually served change on every request, so their durations cannot be taken from that
 * document. We line the episode's ad-free waveform up with the downloaded audio, exactly as for Omny.
 */

/** Envelope rate shared by the Art19 waveform and the decoded audio. */
const val ART19_ENVELOPE_RATE = 16

data class Art19AdInfo(val markersMs: List<Long>, val waveformUrl: String, val cleanDurationMs: Long?)

private val art19EpisodeUrl = Regex(
    """[\w.-]*art19\.com/(?:[^/?#\s]+/)*episodes/([0-9a-fA-F-]{36})(?:\.mp3|/|$)""",
    RegexOption.IGNORE_CASE
)

/** Builds the episode JSON URL from either the feed enclosure or the URL the download redirected to. */
fun art19ClipUrl(audioUrl: String): String? {
    val match = art19EpisodeUrl.find(audioUrl) ?: return null
    return "https://rss.art19.com/episodes/${match.groupValues[1]}.json"
}

/**
 * Reads the fixed ad-break slots. A pod without a start position is a post-roll break, represented
 * as a marker at the end of the ad-free audio, which alignAdBreaks resolves as trailing content.
 */
fun parseArt19Clip(json: String): Art19AdInfo? {
    val root = JSONObject(json)
    val pods = root.optJSONArray("ad_pods") ?: return null
    val content = root.optJSONObject("content") ?: return null
    val waveform = content.optJSONObject("media")
        ?.optJSONObject("waveform_bin")
        ?.optString("url")
        ?.takeIf { httpUrl(it) } ?: return null
    val durationSec = content.optDouble("duration", -1.0)
    val cleanDurationMs = if (durationSec > 0) (durationSec * 1000.0).toLong() else null
    val markers = ArrayList<Long>(pods.length())
    for (i in 0 until pods.length()) {
        val pod = pods.optJSONObject(i) ?: continue
        val start = pod.opt("start_position")
        if (start is Number) {
            val ms = (start.toDouble() * 1000.0).toLong()
            if (ms >= 0) markers.add(ms)
        } else if (cleanDurationMs != null) {
            markers.add(cleanDurationMs)
        }
    }
    if (markers.isEmpty()) return null
    return Art19AdInfo(markers.sorted(), waveform, cleanDurationMs)
}

/**
 * Parses a BBC audiowaveform .dat file (version 1, 8-bit): five little-endian int32 header fields
 * followed by signed min/max byte pairs. Resamples the peaks to [rate] values per second so the
 * result can be compared with the envelope produced while decoding the downloaded audio.
 */
fun parseArt19Waveform(bytes: ByteArray, rate: Int = ART19_ENVELOPE_RATE): Pair<Int, IntArray> {
    require(bytes.size >= 20) { "Short Art19 waveform" }
    val version = leInt(bytes, 0)
    val flags = leInt(bytes, 4)
    val sampleRate = leInt(bytes, 8)
    val samplesPerPixel = leInt(bytes, 12)
    val length = leInt(bytes, 16)
    require(version == 1 && flags == 1) { "Unsupported Art19 waveform version" }
    require(sampleRate > 0 && samplesPerPixel > 0 && length > 0) { "Invalid Art19 waveform header" }
    require(rate in 1..100) { "Unexpected waveform rate" }
    require(bytes.size.toLong() >= 20L + length.toLong() * 2) { "Truncated Art19 waveform" }
    val durationMs = length.toLong() * samplesPerPixel * 1000L / sampleRate
    val out = IntArray((durationMs * rate / 1000L).toInt().coerceAtLeast(1))
    for (i in 0 until length) {
        val lo = bytes[20 + i * 2].toInt()
        val hi = bytes[21 + i * 2].toInt()
        val peak = maxOf(abs(lo), abs(hi))
        val bucket = (i.toLong() * samplesPerPixel * rate / sampleRate).toInt().coerceIn(0, out.lastIndex)
        if (peak > out[bucket]) out[bucket] = peak
    }
    return rate to out
}

private fun leInt(bytes: ByteArray, offset: Int): Int =
    (bytes[offset].toInt() and 0xFF) or
        ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
        ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
        ((bytes[offset + 3].toInt() and 0xFF) shl 24)
