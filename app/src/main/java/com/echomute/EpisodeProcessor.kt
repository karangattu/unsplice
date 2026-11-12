package com.echomute

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import kotlin.math.abs

private val analysisSemaphore = Semaphore(1)

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
        try {
            reporter.beginStage("Checking ad markers and chapters")
            val rssDurationMs = episode.durationMs
            val actualDurationMs = probeAudioDurationMs(audio) ?: rssDurationMs
            if (rssDurationMs == null && actualDurationMs != null) {
                episode = episode.copy(durationMs = actualDurationMs)
            }

            var foundChapterSponsors = false
            try {
                val embedded = audio.inputStream().buffered().use { readId3Chapters(it) }
                val embeddedSegments = if (embedded.isNotEmpty()) sponsorSegments(embedded, actualDurationMs) else emptyList()
                val (chapters, segments, confidence) = if (embeddedSegments.isNotEmpty()) {
                    Triple(embedded, embeddedSegments, 0.9f)
                } else {
                    val jsonChapters = episode.chaptersUrl?.let { fetchJsonChapters(it) }.orEmpty()
                    val jsonSegments = if (jsonChapters.isNotEmpty()) sponsorSegments(jsonChapters, actualDurationMs) else emptyList()
                    when {
                        jsonSegments.isNotEmpty() -> {
                            val stable = rssDurationMs != null && actualDurationMs != null && abs(actualDurationMs - rssDurationMs) <= 5_000L
                            Triple(jsonChapters, jsonSegments, if (stable) 0.85f else 0.7f)
                        }
                        embedded.isNotEmpty() -> Triple(embedded, emptyList(), 0.9f)
                        else -> Triple(jsonChapters, emptyList(), 0.7f)
                    }
                }
                if (chapters.isNotEmpty()) writeChapters(File(directory, "chapters.tsv"), chapters)
                if (segments.isNotEmpty()) {
                    saveNewSegments(dao, episode.id, segments, "chapter", confidence)
                    if (confidence >= 0.85f) foundChapterSponsors = true
                }
            } catch (_: Exception) {
            }

            var omnyInfo: OmnyAdInfo? = null
            var omnyRate = 0
            var omnyClean: IntArray? = null
            if (!foundChapterSponsors) {
                try {
                    val clip = omnyClip(episode.audioUrl)
                    val info = clip?.let { fetchOmnyAdInfo(it) }
                    if (info != null && info.markersMs.isNotEmpty()) {
                        val (rate, clean) = fetchOmnyWaveform(info.waveformUrl)
                        val cleanDurationMs = clean.size * 1000L / rate
                        if (actualDurationMs == null || actualDurationMs > cleanDurationMs + 5_000L) {
                            omnyInfo = info
                            omnyRate = rate
                            omnyClean = clean
                        }
                    }
                } catch (_: Exception) {
                }
            }

            if (!foundChapterSponsors) {
                analysisSemaphore.withPermit {
                    reporter.beginStage("Decoding audio")
                    val analysis = try {
                        decodeAudioForAnalysis(
                            source = audio,
                            targetRate = 8000,
                            envelopeRate = omnyRate,
                            fingerprint = true
                        ) { done, total -> reporter.report(done, total) }
                    } catch (e: InterruptedException) {
                        throw e
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }

                    if (analysis != null) {
                        if (episode.durationMs == null && analysis.durationMs != null) {
                            episode = episode.copy(durationMs = analysis.durationMs)
                        }

                        var foundMarkers = false
                        if (omnyInfo != null && omnyClean != null && analysis.envelope.isNotEmpty()) {
                            try {
                                val (strong, weak) = alignAdBreaks(omnyInfo.markersMs, omnyClean, analysis.envelope, omnyRate)
                                    .partition { it.match >= 0.4 }
                                saveNewSegments(dao, episode.id, strong.map { it.segment }, "marker", 0.9f)
                                saveNewSegments(dao, episode.id, weak.map { it.segment }, "marker", 0.4f)
                                foundMarkers = strong.isNotEmpty()
                            } catch (_: Exception) {
                            }
                        }

                        if (!foundMarkers && analysis.fingerprints.isNotEmpty()) {
                            try {
                                reporter.beginStage("Scanning for repeated ad audio")
                                val packed = analysis.fingerprints
                                writePackedFingerprints(fingerprints, packed)
                                val intra = MatchFinder.intraEpisodePacked(packed)
                                if (intra.isNotEmpty()) {
                                    saveNewSegments(dao, episode.id, intra)
                                }
                                val currentIndex = PackedHashIndex(packed)
                                val candidates = selectComparisonCandidates(episode, dao.episodesWithFingerprints())
                                for ((idx, other) in candidates.withIndex()) {
                                    val otherFile = other.fingerprintPath?.let(::File) ?: continue
                                    if (!otherFile.isFile) continue
                                    val previous = readPackedFingerprints(otherFile)
                                    if (previous.isEmpty()) continue
                                    val (forCurrent, forOther) = MatchFinder.crossEpisodeBidirectional(currentIndex, previous)
                                    if (forCurrent.isNotEmpty()) saveNewSegments(dao, episode.id, forCurrent)
                                    if (forOther.isNotEmpty()) saveNewSegments(dao, other.id, forOther)
                                    reporter.report((idx + 1).toLong(), candidates.size.toLong())
                                }
                                reporter.report(1, 1)
                                fingerprintPath = fingerprints.absolutePath
                            } catch (_: Exception) {
                            }
                        }
                    }
                }
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

internal fun selectComparisonCandidates(
    current: Episode,
    allWithFingerprints: List<Episode>,
    sameShowLimit: Int = 24,
    otherShowLimit: Int = 16
): List<Episode> {
    val eligible = allWithFingerprints.filter { it.id != current.id && !it.fingerprintPath.isNullOrBlank() }
    val (sameShow, otherShow) = eligible.partition { it.podcastId == current.podcastId }
    val byRecency = compareByDescending<Episode> { it.publishedAt ?: Long.MIN_VALUE }
    return sameShow.sortedWith(byRecency).take(sameShowLimit) +
        otherShow.sortedWith(byRecency).take(otherShowLimit)
}

private suspend fun saveNewSegments(dao: LibraryDao, episodeId: String, segments: List<Segment>, source: String = "repeat", confidence: Float = 0.6f) {
    if (segments.isEmpty()) return
    val existing = dao.segments(episodeId)
    val fresh = segments.filter { segment -> existing.none { it.startMs < segment.endMs && segment.startMs < it.endMs } }
    if (fresh.isNotEmpty()) dao.saveSegments(fresh.map { AdSegment(episodeId = episodeId, startMs = it.startMs, endMs = it.endMs, source = source, confidence = confidence) })
}

internal fun writePackedFingerprints(file: File, packed: LongArray) {
    BufferedOutputStream(file.outputStream(), 65536).use { out ->
        val buf = ByteArray(12 * 1024)
        var pos = 0
        for (p in packed) {
            val h = (p ushr 32).toInt()
            val t = p and 0xffffffffL
            buf[pos++] = (h ushr 24).toByte()
            buf[pos++] = (h ushr 16).toByte()
            buf[pos++] = (h ushr 8).toByte()
            buf[pos++] = h.toByte()
            buf[pos++] = (t ushr 56).toByte()
            buf[pos++] = (t ushr 48).toByte()
            buf[pos++] = (t ushr 40).toByte()
            buf[pos++] = (t ushr 32).toByte()
            buf[pos++] = (t ushr 24).toByte()
            buf[pos++] = (t ushr 16).toByte()
            buf[pos++] = (t ushr 8).toByte()
            buf[pos++] = t.toByte()
            if (pos == buf.size) {
                out.write(buf, 0, pos)
                pos = 0
            }
        }
        if (pos > 0) out.write(buf, 0, pos)
    }
}

internal fun readPackedFingerprints(file: File): LongArray {
    val bytes = file.readBytes()
    val count = bytes.size / 12
    val result = LongArray(count)
    var off = 0
    for (i in 0 until count) {
        val h = ((bytes[off].toInt() and 0xFF) shl 24) or
            ((bytes[off + 1].toInt() and 0xFF) shl 16) or
            ((bytes[off + 2].toInt() and 0xFF) shl 8) or
            (bytes[off + 3].toInt() and 0xFF)
        val t = ((bytes[off + 4].toLong() and 0xFF) shl 56) or
            ((bytes[off + 5].toLong() and 0xFF) shl 48) or
            ((bytes[off + 6].toLong() and 0xFF) shl 40) or
            ((bytes[off + 7].toLong() and 0xFF) shl 32) or
            ((bytes[off + 8].toLong() and 0xFF) shl 24) or
            ((bytes[off + 9].toLong() and 0xFF) shl 16) or
            ((bytes[off + 10].toLong() and 0xFF) shl 8) or
            (bytes[off + 11].toLong() and 0xFF)
        result[i] = (h.toLong() shl 32) or (t and 0xffffffffL)
        off += 12
    }
    return result
}
