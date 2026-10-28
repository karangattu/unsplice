package com.echomute

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.util.Base64
import org.json.JSONObject

/**
 * Omny Studio (used by iHeart and many large shows) inserts ad breaks into the audio at download time.
 * Its public clip API lists where those breaks go in the ad-free episode, and a loudness waveform of
 * the ad-free audio. Lining that waveform up with the downloaded audio shows how long each break is.
 */
data class OmnyClip(val organizationId: String, val clipId: String) {
    val apiUrl get() = "https://api.omny.fm/orgs/$organizationId/clips/$clipId"
}

data class OmnyAdInfo(val markersMs: List<Long>, val waveformUrl: String)

data class AlignedBreak(val segment: Segment, val match: Double)

private val omnyAudioPath = Regex("""omny\.fm/d/clips/([0-9a-f-]{36})/[0-9a-f-]{36}/([0-9a-f-]{36})/""")

fun omnyClip(audioUrl: String): OmnyClip? =
    omnyAudioPath.find(audioUrl)?.let { OmnyClip(it.groupValues[1], it.groupValues[2]) }

fun parseOmnyClip(json: String): OmnyAdInfo? {
    val clip = JSONObject(json)
    val waveform = clip.optString("WaveformUrl").takeIf { httpUrl(it) } ?: return null
    val markers = clip.optJSONArray("AdMarkers") ?: return null
    val offsets = (0 until markers.length()).mapNotNull { markers.optJSONObject(it)?.optString("Offset")?.let(::parseOffsetMs) }
    return if (offsets.isEmpty()) null else OmnyAdInfo(offsets.sorted(), waveform)
}

/** Parses Omny's waveform JSON: {"SR": values per second, "D": [base64 bytes per channel]}. */
fun parseOmnyWaveform(json: String): Pair<Int, IntArray> {
    val waveform = JSONObject(json)
    val rate = waveform.getInt("SR")
    require(rate in 1..100) { "Unexpected waveform rate" }
    val bytes = Base64.getDecoder().decode(waveform.getJSONArray("D").getString(0))
    return rate to IntArray(bytes.size) { bytes[it].toInt() and 0xFF }
}

/** "00:18:17" or "02:37:05.1890000" to milliseconds. */
private fun parseOffsetMs(value: String): Long? {
    val parts = value.split(':')
    if (parts.size != 3) return null
    val hours = parts[0].toLongOrNull() ?: return null
    val minutes = parts[1].toLongOrNull() ?: return null
    val seconds = parts[2].toDoubleOrNull() ?: return null
    return (hours * 3600 + minutes * 60) * 1000 + (seconds * 1000).toLong()
}

/** Peak level of 8 kHz mono 16-bit PCM, [rate] values per second, to compare with the publisher's waveform. */
fun pcmEnvelope(pcm: File, rate: Int): IntArray {
    val step = 8000 / rate
    val result = ArrayList<Int>()
    DataInputStream(pcm.inputStream().buffered(1 shl 16)).use { input ->
        val buffer = ByteArray(step * 2)
        while (true) {
            try { input.readFully(buffer) } catch (_: EOFException) { break }
            var peak = 0
            for (i in 0 until step) {
                val sample = (buffer[i * 2].toInt() and 0xFF) or (buffer[i * 2 + 1].toInt() shl 8)
                peak = maxOf(peak, kotlin.math.abs(sample))
            }
            result.add(peak)
        }
    }
    return result.toIntArray()
}

/**
 * Finds the inserted ad breaks. [clean] is the ad-free episode's envelope and [downloaded] the
 * downloaded file's, both at [rate] values per second. Breaks are resolved in order: after each
 * marker, the next minute of ad-free audio is slid forward until it lines up with the download.
 */
fun alignAdBreaks(markersMs: List<Long>, clean: IntArray, downloaded: IntArray, rate: Int): List<AlignedBreak> {
    val extra = downloaded.size - clean.size
    if (extra < 5 * rate) return emptyList()
    val window = 60 * rate
    val breaks = mutableListOf<AlignedBreak>()
    var shift = 0
    var weakest = 1.0
    for (markerMs in markersMs.sorted()) {
        val at = (markerMs * rate / 1000).toInt().coerceIn(0, clean.size)
        val start = at + shift
        val length = minOf(window, clean.size - at)
        if (length < 5 * rate) {
            // Post-roll: everything after the last of the episode's own audio.
            val end = downloaded.size - (clean.size - at)
            if (end - start >= 5 * rate) breaks += AlignedBreak(Segment(start.toMs(rate), end.toMs(rate)), weakest)
            break
        }
        var best = -1.0
        var bestDelay = 0
        for (delay in 0..extra - shift) {
            if (start + delay + length > downloaded.size) break
            val r = correlation(clean, at, downloaded, start + delay, length)
            if (r > best) { best = r; bestDelay = delay }
        }
        if (bestDelay >= 5 * rate) {
            breaks += AlignedBreak(Segment(start.toMs(rate), (start + bestDelay).toMs(rate)), best)
            weakest = minOf(weakest, best)
        }
        shift += bestDelay
    }
    return breaks
}

private fun Int.toMs(rate: Int) = this * 1000L / rate

private fun correlation(a: IntArray, aFrom: Int, b: IntArray, bFrom: Int, length: Int): Double {
    var sumA = 0.0; var sumB = 0.0
    for (i in 0 until length) { sumA += a[aFrom + i]; sumB += b[bFrom + i] }
    val meanA = sumA / length; val meanB = sumB / length
    var cov = 0.0; var varA = 0.0; var varB = 0.0
    for (i in 0 until length) {
        val x = a[aFrom + i] - meanA; val y = b[bFrom + i] - meanB
        cov += x * y; varA += x * x; varB += y * y
    }
    return if (varA == 0.0 || varB == 0.0) 0.0 else cov / kotlin.math.sqrt(varA * varB)
}
