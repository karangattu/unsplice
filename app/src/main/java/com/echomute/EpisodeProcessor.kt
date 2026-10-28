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
        episode = episode.copy(status = "READY", localPath = audio.absolutePath, error = null)
        dao.saveEpisode(episode)

        try {
            // Chapters embedded in the file match its timeline. Feed chapters may predate dynamically inserted ads, so trust them less.
            val embedded = audio.inputStream().buffered().use { readId3Chapters(it) }
            val (chapters, confidence) = if (embedded.isNotEmpty()) embedded to 0.9f
                else episode.chaptersUrl?.let { fetchJsonChapters(it) to 0.7f } ?: (emptyList<Chapter>() to 0f)
            saveNewSegments(dao, episode.id, sponsorSegments(chapters, durationMs(audio)), "chapter", confidence)
        } catch (_: Exception) {
        }

        try {
            decodeAudio(audio, pcm)
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
                val intra = MatchFinder.intraEpisode(current)
                saveNewSegments(dao, episode.id, intra)
                for (other in dao.episodesForPodcast(episode.podcastId)) {
                    if (other.id == episode.id || other.fingerprintPath == null) continue
                    val otherFile = File(other.fingerprintPath)
                    if (!otherFile.isFile) continue
                    val previous = readFingerprints(otherFile)
                    saveNewSegments(dao, episode.id, MatchFinder.crossEpisode(current, previous))
                    saveNewSegments(dao, other.id, MatchFinder.crossEpisode(previous, current))
                }
                dao.saveEpisode(episode.copy(status = "READY", fingerprintPath = fingerprints.absolutePath))
            }
        } catch (_: Exception) {
            dao.saveEpisode(episode.copy(status = "READY", error = null))
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
