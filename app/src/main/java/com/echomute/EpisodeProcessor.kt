package com.echomute

import android.content.Context
import android.media.MediaMetadataRetriever
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File

suspend fun processEpisode(
    context: Context,
    dao: LibraryDao,
    episodeId: String,
    onProgress: (EpisodeProgress) -> Unit = {}
) = withContext(Dispatchers.IO) {
    require(episodeId.matches(Regex("[a-zA-Z0-9_-]+")))
    var episode = requireNotNull(dao.episode(episodeId))
    val directory = File(context.filesDir, "episodes/$episodeId").apply { mkdirs() }
    val audio = File(directory, "audio")
    val partial = File(directory, "audio.part")
    val pcm = File(directory, "audio.pcm")
    val fingerprints = File(directory, "fingerprints.bin")
    val reporter = StepProgressReporter(episodeId, onProgress)
    try {
        if (!audio.isFile) {
            reporter.beginStage("Downloading episode")
            episode = episode.copy(status = "DOWNLOADING", error = null)
            dao.saveEpisode(episode)
            downloadAudio(episode.audioUrl, partial) { done, total ->
                reporter.report(done, total, includeBytes = true)
            }
            check(partial.renameTo(audio)) { "Could not save downloaded audio" }
            File(partial.path + ".etag").delete()
        }
        episode = episode.copy(status = "PROCESSING", localPath = audio.absolutePath, error = null)
        dao.saveEpisode(episode)

        var fingerprintPath: String? = null
        var decoded = false
        try {
            reporter.beginStage("Decoding audio")
            decoded = try {
                decodeAudio(audio, pcm) { done, total -> reporter.report(done, total) }
                true
            } catch (e: InterruptedException) {
                throw e
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { false }

            reporter.beginStage("Checking ad markers and chapters")
            var foundMarkers = false
            if (decoded) try {
                val (strong, weak) = omnyAdBreaks(episode.audioUrl, pcm).partition { it.match >= 0.4 }
                saveNewSegments(dao, episode.id, strong.map { it.segment }, "marker", 0.9f)
                saveNewSegments(dao, episode.id, weak.map { it.segment }, "marker", 0.4f)
                foundMarkers = strong.isNotEmpty()
            } catch (_: Exception) {
            }

            var foundChapterSponsors = false
            try {
                val embedded = audio.inputStream().buffered().use { readId3Chapters(it) }
                val (chapters, confidence) = if (embedded.isNotEmpty()) embedded to 0.9f
                    else episode.chaptersUrl?.let { fetchJsonChapters(it) to 0.7f } ?: (emptyList<Chapter>() to 0f)
                if (chapters.isNotEmpty()) writeChapters(File(directory, "chapters.tsv"), chapters)
                val segments = sponsorSegments(chapters, durationMs(audio))
                if (segments.isNotEmpty()) {
                    saveNewSegments(dao, episode.id, segments, "chapter", confidence)
                    if (confidence >= 0.85f) foundChapterSponsors = true
                }
            } catch (_: Exception) {
            }

            if (decoded && !foundMarkers && !foundChapterSponsors) try {
                reporter.beginStage("Scanning for repeated ad audio")
                val pcmBytes = pcm.length()
                DataOutputStream(fingerprints.outputStream().buffered()).use { out ->
                    pcm.inputStream().buffered().use { input ->
                        val bytes = ByteArray(8000 * 2 * 60)
                        var offset = 0L
                        var readBytes = 0L
                        while (true) {
                            var size = 0
                            while (size < bytes.size) {
                                val n = input.read(bytes, size, bytes.size - size)
                                if (n < 0) break
                                size += n
                            }
                            if (size == 0) break
                            val packed = NativeFingerprint.generateFingerprintsFromBytes(bytes, size)
                            val points = ArrayList<Fingerprint>(packed.size)
                            for (p in packed) {
                                val fp = unpackFingerprint(p)
                                points.add(Fingerprint(fp.hash, fp.timeMs + offset))
                            }
                            for (fp in selectFingerprints(points)) { out.writeInt(fp.hash); out.writeLong(fp.timeMs) }
                            offset += size / 16L
                            readBytes += size
                            reporter.report(readBytes, pcmBytes)
                        }
                    }
                }
                if (fingerprints.isFile && fingerprints.length() > 0) {
                    val current = readFingerprints(fingerprints)
                    saveNewSegments(dao, episode.id, MatchFinder.intraEpisode(current))
                    if (current.isNotEmpty()) {
                        val currentByHash = current.groupBy { it.hash }
                        for (other in dao.episodesWithFingerprints()) {
                            if (other.id == episode.id || other.fingerprintPath == null) continue
                            val otherFile = File(other.fingerprintPath)
                            if (!otherFile.isFile) continue
                            val previous = readFingerprints(otherFile)
                            if (previous.isEmpty()) continue
                            val otherByHash = previous.groupBy { it.hash }
                            saveNewSegments(dao, episode.id, MatchFinder.crossEpisodeWithPrecomputedMap(current, otherByHash))
                            saveNewSegments(dao, other.id, MatchFinder.crossEpisodeWithPrecomputedMap(previous, currentByHash))
                        }
                    }
                    fingerprintPath = fingerprints.absolutePath
                }
            } catch (_: Exception) {
            }
        } finally {
            dao.saveEpisode(episode.copy(status = "READY", fingerprintPath = fingerprintPath))
        }
    } catch (e: CancellationException) {
        if (!audio.isFile) {
            dao.saveEpisode(episode.copy(status = "NOT_DOWNLOADED", error = null))
        }
        throw e
    } catch (e: Exception) {
        if (!audio.isFile) {
            dao.saveEpisode(episode.copy(status = "FAILED", error = e.message ?: "Download failed"))
        }
        throw e
    } finally {
        EpisodeProgressTracker.clear(episodeId)
        pcm.delete()
    }
}

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
    val count = (file.length() / 12).toInt()
    val result = ArrayList<Fingerprint>(count)
    DataInputStream(file.inputStream().buffered(65536)).use { input ->
        repeat(count) { result.add(Fingerprint(input.readInt(), input.readLong())) }
    }
    return result
}
