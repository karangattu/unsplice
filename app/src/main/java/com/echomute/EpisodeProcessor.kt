package com.echomute

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

suspend fun processEpisode(context: Context, dao: LibraryDao, episodeId: String) = withContext(Dispatchers.IO) {
    require(episodeId.matches(Regex("[a-zA-Z0-9_-]+")))
    var episode = requireNotNull(dao.episode(episodeId))
    val directory = File(context.filesDir, "episodes/$episodeId").apply { mkdirs() }
    val audio = File(directory, "audio")
    val partial = File(directory, "audio.part")
    val pcm = File(directory, "audio.pcm")
    val chunk = File(directory, "chunk.pcm")
    val fingerprints = File(directory, "fingerprints.bin")
    try {
        if (!audio.isFile) {
            episode = episode.copy(status = "DOWNLOADING", error = null)
            dao.saveEpisode(episode)
            downloadAudio(episode.audioUrl, partial)
            check(partial.renameTo(audio)) { "Could not save downloaded audio" }
            File(partial.path + ".etag").delete()
        }
        episode = episode.copy(status = "PROCESSING", localPath = audio.absolutePath, error = null)
        dao.saveEpisode(episode)

        // Sources run from most to least trusted; a later segment that overlaps an earlier one is dropped.
        var fingerprintPath: String? = null
        try {
            val decoded = try { decodeAudio(audio, pcm); true } catch (_: Exception) { false }

            var foundMarkers = false
            if (decoded) try {
                val (strong, weak) = omnyAdBreaks(episode.audioUrl, pcm).partition { it.match >= 0.4 }
                saveNewSegments(dao, episode.id, strong.map { it.segment }, "marker", 0.9f)
                saveNewSegments(dao, episode.id, weak.map { it.segment }, "marker", 0.4f)
                foundMarkers = strong.isNotEmpty()
            } catch (_: Exception) {
            }

            try {
                // Chapters embedded in the file match its timeline. Feed chapters may predate dynamically inserted ads, so trust them less.
                val embedded = audio.inputStream().buffered().use { readId3Chapters(it) }
                val (chapters, confidence) = if (embedded.isNotEmpty()) embedded to 0.9f
                    else episode.chaptersUrl?.let { fetchJsonChapters(it) to 0.7f } ?: (emptyList<Chapter>() to 0f)
                saveNewSegments(dao, episode.id, sponsorSegments(chapters, durationMs(audio)), "chapter", confidence)
            } catch (_: Exception) {
            }

            // Publisher markers already cover every inserted break, so skip the slow fingerprint pass.
            if (decoded && !foundMarkers) try {
                DataOutputStream(fingerprints.outputStream().buffered()).use { out ->
                    pcm.inputStream().buffered().use { input ->
                        val bytes = ByteArray(8000 * 2 * 60)
                        var offset = 0L
                        while (true) {
                            var size = 0
                            while (size < bytes.size) {
                                val n = input.read(bytes, size, bytes.size - size)
                                if (n < 0) break
                                size += n
                            }
                            if (size == 0) break
                            chunk.outputStream().use { it.write(bytes, 0, size) }
                            val points = NativeFingerprint.generateFingerprints(chunk.absolutePath).map { unpackFingerprint(it).let { fp -> fp.copy(timeMs = fp.timeMs + offset) } }
                            for (fp in selectFingerprints(points)) { out.writeInt(fp.hash); out.writeLong(fp.timeMs) }
                            offset += size / 16L
                        }
                    }
                }
                if (fingerprints.isFile && fingerprints.length() > 0) {
                    val current = readFingerprints(fingerprints)
                    saveNewSegments(dao, episode.id, MatchFinder.intraEpisode(current))
                    // Dynamically inserted ads repeat across every show in a network, so compare with the whole library.
                    for (other in dao.episodesWithFingerprints()) {
                        if (other.id == episode.id || other.fingerprintPath == null) continue
                        val otherFile = File(other.fingerprintPath)
                        if (!otherFile.isFile) continue
                        val previous = readFingerprints(otherFile)
                        saveNewSegments(dao, episode.id, MatchFinder.crossEpisode(current, previous))
                        saveNewSegments(dao, other.id, MatchFinder.crossEpisode(previous, current))
                    }
                    fingerprintPath = fingerprints.absolutePath
                }
            } catch (_: Exception) {
            }
        } finally {
            dao.saveEpisode(episode.copy(status = "READY", fingerprintPath = fingerprintPath))
        }
    } catch (e: Exception) {
        if (!audio.isFile) {
            dao.saveEpisode(episode.copy(status = "FAILED", error = e.message ?: "Download failed"))
        }
        throw e
    } finally {
        pcm.delete()
        chunk.delete()
    }
}

/** Saves segments that don't overlap ones already stored, so the first (most trusted) source wins. */
private suspend fun saveNewSegments(dao: LibraryDao, episodeId: String, segments: List<Segment>, source: String = "repeat", confidence: Float = 0.6f) {
    val existing = dao.segments(episodeId)
    val fresh = segments.filter { segment -> existing.none { it.startMs < segment.endMs && segment.startMs < it.endMs } }
    if (fresh.isNotEmpty()) dao.saveSegments(fresh.map { AdSegment(episodeId = episodeId, startMs = it.startMs, endMs = it.endMs, source = source, confidence = confidence) })
}

private fun omnyAdBreaks(audioUrl: String, pcm: File): List<AlignedBreak> {
    val clip = omnyClip(audioUrl) ?: return emptyList()
    val info = fetchOmnyAdInfo(clip) ?: return emptyList()
    val (rate, clean) = fetchOmnyWaveform(info.waveformUrl)
    return alignAdBreaks(info.markersMs, clean, pcmEnvelope(pcm, rate), rate)
}

private fun durationMs(audio: File): Long? {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(audio.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    } catch (_: Exception) { null } finally { retriever.release() }
}

private fun readFingerprints(file: File): List<Fingerprint> {
    val result = ArrayList<Fingerprint>()
    DataInputStream(file.inputStream().buffered()).use { input ->
        repeat((file.length() / 12).toInt()) { result.add(Fingerprint(input.readInt(), input.readLong())) }
    }
    return result
}
