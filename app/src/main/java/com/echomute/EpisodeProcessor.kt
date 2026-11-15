package com.echomute

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import kotlin.math.abs

private val analysisSemaphore = Semaphore(1)

internal data class ChapterResolution(
    val chapters: List<Chapter>,
    val immediateSegments: List<Pair<Segment, Float>>,
    val deferredSegments: List<Pair<Segment, Float>>,
    val cueFallbackSegments: List<Pair<Segment, Float>>,
    val skipAudioDecode: Boolean
)

/** Publisher markers plus the ad-free envelope they refer to, ready for alignAdBreaks. */
internal data class MarkerTarget(
    val markersMs: List<Long>,
    val clean: IntArray,
    val rate: Int
)

internal fun corroborateWithSplices(
    scored: List<Pair<Segment, Float>>,
    spliceSegments: List<Pair<Segment, Float>>,
    timestampsAligned: Boolean
): List<Pair<Segment, Float>> {
    if (!timestampsAligned || spliceSegments.isEmpty()) return scored
    return scored.map { (segment, confidence) ->
        if (confidence < 0.4f || confidence >= AUTO_SKIP_CONFIDENCE) return@map segment to confidence
        val matches = spliceSegments.singleOrNull { (splice, _) ->
            abs(splice.startMs - segment.startMs) <= 2_000L &&
                abs(splice.endMs - segment.endMs) <= 2_000L
        }
        if (matches == null) segment to confidence else matches.first to 0.70f
    }
}

private fun sponsorSegmentsWithConfidence(
    chapters: List<Chapter>,
    durationMs: Long?,
    baseConfidence: Float,
    trusted: Boolean
): List<Pair<Segment, Float>> {
    if (chapters.isEmpty()) return emptyList()
    val sorted = chapters.sortedBy { it.startMs }
    val out = ArrayList<Pair<Segment, Float>>()
    for (i in sorted.indices) {
        val chapter = sorted[i]
        if (!isSponsorChapter(chapter)) continue
        val end = chapter.endMs ?: sorted.getOrNull(i + 1)?.startMs ?: durationMs ?: continue
        if (end <= chapter.startMs) continue
        val conf = if (trusted) sponsorConfidence(chapter, baseConfidence) else baseConfidence
        out.add(Segment(chapter.startMs, end) to conf)
    }
    return out
}

internal fun resolveChaptersAndMetadata(
    episode: Episode,
    audio: File,
    probedDurationMs: Long?,
    rssDurationMs: Long?,
    fetchJson: (String) -> List<Chapter> = ::fetchJsonChapters
): ChapterResolution {
    val effectiveDurationMs = probedDurationMs ?: episode.durationMs ?: rssDurationMs
    val id3 = try {
        audio.inputStream().buffered().use { readId3Metadata(it) }
    } catch (_: Exception) {
        Id3Metadata(emptyList())
    }
    val embeddedSegments = if (id3.chapters.isNotEmpty()) {
        sponsorSegmentsWithConfidence(id3.chapters, effectiveDurationMs, 0.9f, trusted = true)
    } else emptyList()

    val cueFallbackSegments = id3.adBreakCuesMs.mapNotNull { cueMs ->
        if (effectiveDurationMs != null && cueMs >= effectiveDurationMs) return@mapNotNull null
        val endMs = minOf(cueMs + 30_000L, effectiveDurationMs ?: (cueMs + 30_000L))
        if (endMs > cueMs) Segment(cueMs, endMs) to 0.4f else null
    }

    if (embeddedSegments.isNotEmpty()) {
        return ChapterResolution(
            chapters = id3.chapters,
            immediateSegments = embeddedSegments,
            deferredSegments = emptyList(),
            cueFallbackSegments = cueFallbackSegments,
            skipAudioDecode = true
        )
    }

    val trusted = hasTrustedJsonChapters(probedDurationMs, rssDurationMs)
    val baseConfidence = if (trusted) 0.85f else 0.4f

    val pscChapters = try {
        episode.pscChaptersJson?.let(::parseJsonChapters).orEmpty()
    } catch (_: Exception) {
        emptyList()
    }
    val jsonChapters = try {
        episode.chaptersUrl?.let(fetchJson).orEmpty()
    } catch (_: Exception) {
        emptyList()
    }

    val jsonSegments = sponsorSegmentsWithConfidence(jsonChapters, effectiveDurationMs, baseConfidence, trusted)
    val pscSegments = if (jsonSegments.isEmpty()) {
        sponsorSegmentsWithConfidence(pscChapters, effectiveDurationMs, baseConfidence, trusted)
    } else emptyList()

    val hasStructuredChapters = id3.chapters.isNotEmpty() || jsonChapters.isNotEmpty() || pscChapters.isNotEmpty()
    val showNoteChapters = if (!hasStructuredChapters) {
        extractTimestampedChapters(episode.description, effectiveDurationMs)
    } else emptyList()
    val showNoteSegments = if (showNoteChapters.isNotEmpty()) {
        sponsorSegments(showNoteChapters, effectiveDurationMs).map { it to 0.4f }
    } else emptyList()

    val displayChapters = when {
        jsonSegments.isNotEmpty() -> jsonChapters
        pscSegments.isNotEmpty() -> pscChapters
        id3.chapters.isNotEmpty() -> id3.chapters
        jsonChapters.isNotEmpty() -> jsonChapters
        pscChapters.isNotEmpty() -> pscChapters
        else -> showNoteChapters
    }

    val structuredSegments = jsonSegments.ifEmpty { pscSegments }
    val skipAudioDecode = trusted && structuredSegments.isNotEmpty()
    val immediateSegments = if (skipAudioDecode) structuredSegments else emptyList()
    val deferredSegments = if (skipAudioDecode) emptyList() else structuredSegments.ifEmpty { showNoteSegments }

    return ChapterResolution(
        chapters = displayChapters,
        immediateSegments = immediateSegments,
        deferredSegments = deferredSegments,
        cueFallbackSegments = cueFallbackSegments,
        skipAudioDecode = skipAudioDecode
    )
}

internal fun resolveOmnyTarget(
    candidateUrls: List<String>,
    probedDurationMs: Long?,
    clipFetcher: (String) -> OmnyAdInfo? = ::fetchOmnyClip,
    waveformFetcher: (String) -> Pair<Int, IntArray> = ::fetchOmnyWaveform
): MarkerTarget? {
    for (url in candidateUrls.distinct()) {
        val target = resolveOmnyTarget(url, probedDurationMs, clipFetcher, waveformFetcher)
        if (target != null) return target
    }
    return null
}

internal fun resolveOmnyTarget(
    resolvedUrl: String,
    probedDurationMs: Long?,
    clipFetcher: (String) -> OmnyAdInfo? = ::fetchOmnyClip,
    waveformFetcher: (String) -> Pair<Int, IntArray> = ::fetchOmnyWaveform
): MarkerTarget? {
    val info = clipFetcher(resolvedUrl) ?: return null
    return markerTarget(info.markersMs, info.waveformUrl, info.durationMs, probedDurationMs, waveformFetcher)
}

/** ART19 hides its ad breaks in a separate episode JSON instead of the RSS feed. */
internal fun resolveArt19Target(
    candidateUrls: List<String>,
    probedDurationMs: Long?,
    clipFetcher: (String) -> Art19AdInfo? = ::fetchArt19Clip,
    waveformFetcher: (String) -> Pair<Int, IntArray> = ::fetchArt19Waveform
): MarkerTarget? {
    for (url in candidateUrls.distinct()) {
        val target = resolveArt19Target(url, probedDurationMs, clipFetcher, waveformFetcher)
        if (target != null) return target
    }
    return null
}

/** ART19 hides its ad breaks in a separate episode JSON instead of the RSS feed. */
internal fun resolveArt19Target(
    resolvedUrl: String,
    probedDurationMs: Long?,
    clipFetcher: (String) -> Art19AdInfo? = ::fetchArt19Clip,
    waveformFetcher: (String) -> Pair<Int, IntArray> = ::fetchArt19Waveform
): MarkerTarget? {
    val info = clipFetcher(resolvedUrl) ?: return null
    return markerTarget(info.markersMs, info.waveformUrl, info.cleanDurationMs, probedDurationMs, waveformFetcher)
}

private fun markerTarget(
    markersMs: List<Long>,
    waveformUrl: String,
    declaredDurationMs: Long?,
    probedDurationMs: Long?,
    waveformFetcher: (String) -> Pair<Int, IntArray>
): MarkerTarget? {
    if (markersMs.isEmpty()) return null
    // If the download is no longer than the ad-free episode, no ads were stitched in.
    if (declaredDurationMs != null && probedDurationMs != null && probedDurationMs <= declaredDurationMs + 5_000L) return null
    val (rate, clean) = waveformFetcher(waveformUrl)
    val cleanDurationMs = clean.size * 1000L / rate
    if (probedDurationMs != null && probedDurationMs <= cleanDurationMs + 5_000L) return null
    return MarkerTarget(markersMs, clean, rate)
}

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
        val candidateUrls = mutableListOf(episode.audioUrl)
        if (!audio.isFile) {
            reporter.beginStage("Downloading episode")
            episode = episode.copy(status = "DOWNLOADING", error = null)
            dao.saveEpisode(episode)
            val downloadResult = downloadAudioWithUrl(episode.audioUrl, partial) { done, total ->
                reporter.report(done, total, includeBytes = true)
            }
            candidateUrls.addAll(downloadResult.redirectUrls)
            candidateUrls.add(downloadResult.finalUrl)
            check(partial.renameTo(audio)) { "Could not save downloaded audio" }
            File(partial.path + ".etag").delete()
        }
        episode = episode.copy(status = "PROCESSING", localPath = audio.absolutePath, error = null)
        dao.saveEpisode(episode)

        var fingerprintPath: String? = null
        try {
            reporter.beginStage("Checking ad markers and chapters")
            val rssDurationMs = episode.rssDurationMs
            val probedDurationMs = try { probeAudioDurationMs(audio) } catch (_: Exception) { null }
            if (probedDurationMs != null) episode = episode.copy(durationMs = probedDurationMs)

            val chapterRes = try {
                resolveChaptersAndMetadata(episode, audio, probedDurationMs, rssDurationMs)
            } catch (_: Exception) {
                ChapterResolution(emptyList(), emptyList(), emptyList(), emptyList(), false)
            }
            if (chapterRes.chapters.isNotEmpty()) {
                writeChapters(File(directory, "chapters.tsv"), chapterRes.chapters)
            }
            if (chapterRes.immediateSegments.isNotEmpty()) {
                saveNewSegmentsScored(dao, episode.id, chapterRes.immediateSegments, "chapter")
            }
            val foundChapterSponsors = chapterRes.skipAudioDecode

            val markerTarget = if (!foundChapterSponsors) {
                val omny = try {
                    resolveOmnyTarget(candidateUrls, probedDurationMs)
                } catch (_: Exception) {
                    null
                }
                omny ?: try {
                    resolveArt19Target(candidateUrls, probedDurationMs)
                } catch (_: Exception) {
                    null
                }
            } else null

            var analysisDurationMs = probedDurationMs
            val weakMarkerSegments = mutableListOf<Pair<Segment, Float>>()
            var analysisEnvelope = IntArray(0)
            val envRate = markerTarget?.rate ?: 10
            var foundMarkers = false
            var foundRepeat = false

            if (!foundChapterSponsors) {
                analysisSemaphore.withPermit {
                    reporter.beginStage("Decoding audio")
                    val analysis = try {
                        decodeAudioForAnalysis(
                            source = audio,
                            targetRate = 8000,
                            envelopeRate = envRate,
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
                        analysisEnvelope = analysis.envelope
                        if (analysis.durationMs != null) {
                            analysisDurationMs = analysisDurationMs ?: analysis.durationMs
                            if (episode.durationMs == null) {
                                episode = episode.copy(durationMs = analysis.durationMs)
                            }
                        }

                        if (markerTarget != null && analysis.envelope.isNotEmpty()) {
                            try {
                                val (strong, weak) = alignAdBreaks(
                                    markerTarget.markersMs,
                                    markerTarget.clean,
                                    analysis.envelope,
                                    markerTarget.rate
                                ).partition { it.match >= 0.4 }
                                saveNewSegments(dao, episode.id, strong.map { it.segment }, "marker", 0.9f)
                                for (w in weak) weakMarkerSegments.add(w.segment to 0.4f)
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
                                    foundRepeat = true
                                }
                                val currentIndex = PackedHashIndex(packed)
                                val candidates = selectComparisonCandidates(episode, dao.episodesWithFingerprints())
                                for ((idx, other) in candidates.withIndex()) {
                                    val otherFile = other.fingerprintPath?.let(::File) ?: continue
                                    if (!otherFile.isFile) continue
                                    val previous = readPackedFingerprints(otherFile)
                                    if (previous.isEmpty()) continue
                                    val (forCurrent, forOther) = MatchFinder.crossEpisodeBidirectional(currentIndex, previous)
                                    if (forCurrent.isNotEmpty()) {
                                        saveNewSegments(dao, episode.id, forCurrent)
                                        foundRepeat = true
                                    }
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

            val timestampsAligned = hasTrustedJsonChapters(analysisDurationMs, rssDurationMs)
            val spliceSegments = if (!foundChapterSponsors && !foundMarkers) {
                analysisSemaphore.withPermit {
                    val scanContext = currentCoroutineContext()
                    try {
                        Mp3SpliceDetector.detect(audio) { scanContext.ensureActive() }
                    } catch (_: IOException) {
                        emptyList()
                    }
                }
            } else emptyList()

            val rawTranscriptSegments = if (!episode.transcriptUrl.isNullOrBlank()) {
                try {
                    val content = fetchTranscript(episode.transcriptUrl!!)
                    detectTranscriptAds(
                        transcriptContent = content,
                        type = episode.transcriptType,
                        actualDurationMs = analysisDurationMs,
                        rssDurationMs = rssDurationMs,
                        filesDir = context.filesDir,
                        episodeId = episode.id
                    ).segments
                } catch (_: Exception) {
                    emptyList()
                }
            } else emptyList()

            val transcriptSegments = AcousticAdDetector.refineTranscriptSegments(
                corroborateWithSplices(rawTranscriptSegments, spliceSegments, timestampsAligned),
                analysisEnvelope, envRate, timestampsAligned
            )
            val chapterSegments = corroborateWithSplices(chapterRes.deferredSegments, spliceSegments, timestampsAligned)
            val cueFallbacks = corroborateWithSplices(chapterRes.cueFallbackSegments, spliceSegments, timestampsAligned)
            val acousticBreaks = if (!foundChapterSponsors && !foundMarkers && !foundRepeat) {
                AcousticAdDetector.detectLoudnessJumpBreaks(analysisEnvelope, envRate)
            } else emptyList()

            val candidates = buildList {
                addAll(chapterSegments.asAdSegments(episode.id, "chapter"))
                addAll(weakMarkerSegments.asAdSegments(episode.id, "marker"))
                addAll(transcriptSegments.asAdSegments(episode.id, "transcript"))
                addAll(cueFallbacks.asAdSegments(episode.id, "marker"))
                addAll(spliceSegments.asAdSegments(episode.id, "splice"))
                addAll(acousticBreaks.asAdSegments(episode.id, "acoustic"))
            }
            if (candidates.isNotEmpty()) dao.saveNewSegments(episode.id, candidates)
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
    saveNewSegmentsScored(dao, episodeId, segments.map { it to confidence }, source)
}

internal suspend fun saveNewSegmentsScored(
    dao: LibraryDao, episodeId: String, scored: List<Pair<Segment, Float>>, source: String
) {
    if (scored.isNotEmpty()) dao.saveNewSegments(episodeId, scored.asAdSegments(episodeId, source))
}

private fun List<Pair<Segment, Float>>.asAdSegments(episodeId: String, source: String): List<AdSegment> =
    map { (segment, confidence) ->
        AdSegment(episodeId = episodeId, startMs = segment.startMs, endMs = segment.endMs, source = source, confidence = confidence)
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
