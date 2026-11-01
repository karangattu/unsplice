package com.echomute

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object NativeWhisper {
    init { System.loadLibrary("echomute") }
    /** Loads a ggml model (and remembers the optional VAD model); returns 0 if the file is missing or corrupt. */
    external fun load(modelPath: String, vadModelPath: String?, backendDir: String): Long
    external fun release(handle: Long)
    /** Stops the running or next [transcribe] call on this session. */
    external fun cancel(handle: Long)
    external fun progress(handle: Long): Int
    /** Description of the CPU backend ggml picked, such as `android_armv8.2_1`; call after [load]. */
    external fun backendName(): String
    /** Transcribes [count] 16 kHz samples from [offset] in a 16-bit PCM file; returns UTF-8 "startMs\tendMs\ttext" lines. */
    external fun transcribe(handle: Long, pcmPath: String, offset: Long, count: Int, threads: Int, audioCtx: Int): ByteArray
}

const val WHISPER_SAMPLE_RATE = 16_000
/** English-only tiny model, 8-bit quantised (44 MB). Downloaded on first use, not bundled, to keep the APK small. */
const val WHISPER_MODEL_NAME = "ggml-tiny.en-q8_0.bin"
const val WHISPER_MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$WHISPER_MODEL_NAME"
/**
 * Silero voice activity model (0.9 MB), so music and silence are not transcribed. Off by default: on a talk
 * podcast it made transcription 17% slower for no gain. Turn it on for feeds with long music beds.
 */
const val WHISPER_USE_VAD = false
const val WHISPER_VAD_MODEL_NAME = "ggml-silero-v6.2.0.bin"
const val WHISPER_VAD_MODEL_URL = "https://huggingface.co/ggml-org/whisper-vad/resolve/main/$WHISPER_VAD_MODEL_NAME"
/**
 * Encoder context per 30 s chunk; 0 is whisper's full 1500. Reducing it (768) halves encoder work but sends
 * `tiny.en` into repetition loops on some audio, which then takes far longer than it saves. Leave at 0.
 */
const val WHISPER_AUDIO_CTX = 0
/** Loudness envelope resolution used to cut windows and refine segment edges. */
const val ENVELOPE_FRAME_MS = 100

/** Audio handed to whisper per call. Longer windows cost memory (4 bytes per sample) and delay cancellation. */
private const val WINDOW_SAMPLES = 5 * 60 * WHISPER_SAMPLE_RATE
/** A window is cut at the quietest point in its last ten seconds so words are not split. */
private const val CUT_SEARCH_MS = 10_000

/**
 * Sponsor reads found from the episode's wording, each with the confidence to store it at. [pcm] is 16 kHz mono
 * 16-bit PCM. The transcript is kept in [directory] as `transcript.tsv`, so "Scan again" reclassifies without
 * transcribing again.
 */
suspend fun transcriptSegments(
    context: Context,
    pcm: File,
    directory: File,
    reporter: StepProgressReporter? = null
): List<Pair<Segment, Float>> {
    val transcript = File(directory, "transcript.tsv")
    val envelope = loudnessEnvelope(pcm)
    val words = if (transcript.isFile) readTranscript(transcript) else {
        val modelFile = File(context.filesDir, "models/$WHISPER_MODEL_NAME")
        if (!modelFile.isFile) reporter?.beginStage("Downloading speech model")
        val model = whisperModel(context) { done, total -> reporter?.report(done, total, includeBytes = true) }
        val vad = if (WHISPER_USE_VAD) try { whisperVadModel(context) } catch (_: IOException) { null } else null
        reporter?.beginStage("Transcribing audio for sponsor reads")
        val started = System.currentTimeMillis()
        transcribePcm(model, vad, pcm, envelope, context.applicationInfo.nativeLibraryDir) { done, total ->
            reporter?.report(done, total)
        }.also {
            writeTranscript(transcript, it)
            Log.i("Transcript", "Transcribed ${pcm.length() / (2 * WHISPER_SAMPLE_RATE)} s of audio in ${(System.currentTimeMillis() - started) / 1000} s on ${NativeWhisper.backendName()}")
        }
    }
    return sponsorReads(words).map { refineEdges(it.segment, envelope) to transcriptConfidence(it.score) }
}

/** Returns the local speech model, downloading it once if needed. */
fun whisperModel(context: Context, onProgress: (Long, Long) -> Unit = { _, _ -> }) =
    downloadedModel(context, WHISPER_MODEL_NAME, WHISPER_MODEL_URL, onProgress)

/** Returns the local voice activity model, downloading it once if needed. */
fun whisperVadModel(context: Context, onProgress: (Long, Long) -> Unit = { _, _ -> }) =
    downloadedModel(context, WHISPER_VAD_MODEL_NAME, WHISPER_VAD_MODEL_URL, onProgress)

private fun downloadedModel(context: Context, name: String, url: String, onProgress: (Long, Long) -> Unit = { _, _ -> }): File {
    val model = File(context.filesDir, "models/$name")
    if (model.isFile) return model
    val partial = File(model.path + ".part")
    downloadAudio(url, partial, onProgress)
    check(partial.renameTo(model)) { "Could not save model $name" }
    File(partial.path + ".etag").delete()
    return model
}

/**
 * Transcribes 16 kHz mono 16-bit PCM in [pcm] in windows cut at quiet points of [envelope]. Cancelling the calling
 * coroutine aborts the model within a few hundred milliseconds.
 */
suspend fun transcribePcm(
    model: File,
    vad: File?,
    pcm: File,
    envelope: FloatArray,
    backendDir: String,
    onProgress: (Long, Long) -> Unit = { _, _ -> }
): List<TranscriptWord> = withContext(Dispatchers.IO) {
    val handle = NativeWhisper.load(model.absolutePath, vad?.absolutePath, backendDir)
    if (handle == 0L) {
        model.delete() // A truncated download; fetch it again next time.
        throw IOException("Could not load speech model")
    }
    val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    val words = ArrayList<TranscriptWord>()
    coroutineScope {
    // Transcription blocks this thread, so a sibling coroutine relays cancellation to the model.
    val watcher = launch { try { awaitCancellation() } finally { NativeWhisper.cancel(handle) } }
    try {
        val total = pcm.length() / 2
        var offset = 0L
        while (offset < total) {
            ensureActive()
            val end = if (total - offset <= WINDOW_SAMPLES) total else quietestCut(envelope, offset + WINDOW_SAMPLES)
            val progressPoller = launch {
                while (true) {
                    delay(250)
                    val pct = NativeWhisper.progress(handle).coerceIn(0, 100)
                    if (pct > 0) onProgress(offset + (end - offset) * pct / 100, total)
                }
            }
            val lines = try {
                NativeWhisper.transcribe(handle, pcm.absolutePath, offset, (end - offset).toInt(), threads, WHISPER_AUDIO_CTX)
            } catch (e: IOException) {
                ensureActive()
                throw e
            } finally {
                progressPoller.cancelAndJoin()
            }
            onProgress(end, total)
            val offsetMs = offset * 1000 / WHISPER_SAMPLE_RATE
            for (line in String(lines, Charsets.UTF_8).lineSequence()) {
                val parts = line.split('\t', limit = 3)
                if (parts.size < 3 || parts[2].isBlank()) continue
                words.add(TranscriptWord(parts[0].toLong() + offsetMs, parts[1].toLong() + offsetMs, parts[2].trim()))
            }
            offset = end
        }
    } finally {
        watcher.cancelAndJoin()
        NativeWhisper.release(handle)
    }
    }
    words
}

/** RMS loudness of 16 kHz mono 16-bit PCM per [ENVELOPE_FRAME_MS]. */
fun loudnessEnvelope(pcm: File): FloatArray {
    val frame = WHISPER_SAMPLE_RATE * ENVELOPE_FRAME_MS / 1000
    val result = ArrayList<Float>()
    DataInputStream(pcm.inputStream().buffered(1 shl 16)).use { input ->
        var sum = 0.0
        var count = 0
        while (true) {
            val sample = try { readShort(input).toDouble() } catch (_: EOFException) { break }
            sum += sample * sample
            if (++count == frame) {
                result.add(Math.sqrt(sum / count).toFloat())
                sum = 0.0
                count = 0
            }
        }
        if (count > 0) result.add(Math.sqrt(sum / count).toFloat())
    }
    return result.toFloatArray()
}

fun writeTranscript(file: File, words: List<TranscriptWord>) {
    file.bufferedWriter().use { out -> for (w in words) out.write("${w.startMs}\t${w.endMs}\t${w.text}\n") }
}

fun readTranscript(file: File): List<TranscriptWord> = file.useLines { lines ->
    lines.mapNotNull { line ->
        val parts = line.split('\t', limit = 3)
        if (parts.size < 3) null else TranscriptWord(parts[0].toLong(), parts[1].toLong(), parts[2])
    }.toList()
}

private fun readShort(input: DataInputStream): Short {
    val low = input.read()
    val high = input.read()
    if (low < 0 || high < 0) throw EOFException()
    return ((high shl 8) or low).toShort()
}

/** Sample index of the quietest [ENVELOPE_FRAME_MS] frame in the [CUT_SEARCH_MS] before [endSample]. */
private fun quietestCut(envelope: FloatArray, endSample: Long): Long {
    val frame = WHISPER_SAMPLE_RATE * ENVELOPE_FRAME_MS / 1000
    val last = (endSample / frame).toInt().coerceAtMost(envelope.size) - 1
    val first = (last - CUT_SEARCH_MS / ENVELOPE_FRAME_MS).coerceAtLeast(0)
    var best = last
    for (i in first..last) if (envelope[i] < envelope[best]) best = i
    return best.toLong() * frame
}
