package com.echomute

import android.content.Context
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
                if (intra.isNotEmpty()) {
                    dao.saveSegments(intra.map { AdSegment(episodeId = episode.id, startMs = it.startMs, endMs = it.endMs) })
                }
                for (other in dao.episodesForPodcast(episode.podcastId)) {
                    if (other.id == episode.id || other.fingerprintPath == null) continue
                    val otherFile = File(other.fingerprintPath)
                    if (!otherFile.isFile) continue
                    val previous = readFingerprints(otherFile)
                    val matches = MatchFinder.crossEpisode(current, previous)
                    if (matches.isNotEmpty()) {
                        val existing = dao.segments(episode.id)
                        val newMatches = matches.filter { seg -> existing.none { it.startMs < seg.endMs && seg.startMs < it.endMs } }
                        if (newMatches.isNotEmpty()) dao.saveSegments(newMatches.map { AdSegment(episodeId = episode.id, startMs = it.startMs, endMs = it.endMs) })
                    }
                    val reverse = MatchFinder.crossEpisode(previous, current)
                    val existing = dao.segments(other.id)
                    val newSegments = reverse.filter { segment -> existing.none { it.startMs < segment.endMs && segment.startMs < it.endMs } }
                    if (newSegments.isNotEmpty()) dao.saveSegments(newSegments.map { AdSegment(episodeId = other.id, startMs = it.startMs, endMs = it.endMs) })
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

private fun readFingerprints(file: File): List<Fingerprint> {
    val result = ArrayList<Fingerprint>()
    DataInputStream(file.inputStream().buffered()).use { input ->
        repeat((file.length() / 12).toInt()) { result.add(Fingerprint(input.readInt(), input.readLong())) }
    }
    return result
}
