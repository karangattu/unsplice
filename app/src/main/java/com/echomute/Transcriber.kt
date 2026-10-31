package com.echomute

import android.content.Context
import android.util.Log
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException

object NativeWhisper {
    init { System.loadLibrary("echomute") }
    /** Loads a ggml model; returns 0 if the file is missing or corrupt. */
    external fun load(modelPath: String): Long
    external fun release(handle: Long)
    /** Transcribes 16 kHz mono samples in [-1, 1]; returns UTF-8 "startMs\tendMs\ttext" lines, one per word. */
    external fun transcribe(handle: Long, samples: FloatArray, threads: Int): ByteArray
}

const val WHISPER_SAMPLE_RATE = 16_000
/** English-only tiny model, 5-bit quantised (32 MB). Downloaded on first use, not bundled, to keep the APK small. */
const val WHISPER_MODEL_NAME = "ggml-tiny.en-q5_1.bin"
const val WHISPER_MODEL_URL = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/$WHISPER_MODEL_NAME"
/** Loudness envelope resolution used to refine segment edges. */
const val ENVELOPE_FRAME_MS = 100

/** Audio handed to whisper per call. Longer windows cost memory (4 bytes per sample) and delay cancellation. */
private const val WINDOW_SAMPLES = 5 * 60 * WHISPER_SAMPLE_RATE
/** A window is cut at the quietest point in its last ten seconds so words are not split. */
private const val CUT_SEARCH_SAMPLES = 10 * WHISPER_SAMPLE_RATE

/**
 * Sponsor reads found from the episode's wording, each with the confidence to store it at. The transcript is kept
 * beside the audio as `transcript.tsv`, so "Scan again" reclassifies without transcribing again.
 */
fun transcriptSegments(context: Context, audio: File, directory: File, isActive: () -> Boolean): List<Pair<Segment, Float>> {
    val pcm = File(directory, "audio16k.pcm")
    val transcript = File(directory, "transcript.tsv")
    try {
        val model = if (transcript.isFile) null else whisperModel(context)
        decodeAudio(audio, pcm, WHISPER_SAMPLE_RATE)
        val words = if (model == null) readTranscript(transcript) else {
            val started = System.currentTimeMillis()
            transcribePcm(model, pcm, isActive).also {
                writeTranscript(transcript, it)
                Log.i("Transcript", "Transcribed ${pcm.length() / (2 * WHISPER_SAMPLE_RATE)} s of audio in ${(System.currentTimeMillis() - started) / 1000} s")
            }
        }
        val envelope = loudnessEnvelope(pcm)
        return sponsorReads(words).map { refineEdges(it.segment, envelope) to transcriptConfidence(it.score) }
    } finally {
        pcm.delete()
    }
}

/** Returns the local model file, downloading it once if needed. */
fun whisperModel(context: Context): File {
    val model = File(context.filesDir, "models/$WHISPER_MODEL_NAME")
    if (model.isFile) return model
    val partial = File(model.path + ".part")
    downloadAudio(WHISPER_MODEL_URL, partial)
    check(partial.renameTo(model)) { "Could not save speech model" }
    File(partial.path + ".etag").delete()
    return model
}

/** Transcribes 16 kHz mono 16-bit PCM in [pcm], checking [isActive] between windows. */
fun transcribePcm(model: File, pcm: File, isActive: () -> Boolean = { true }): List<TranscriptWord> {
    val handle = NativeWhisper.load(model.absolutePath)
    if (handle == 0L) {
        model.delete() // A truncated download; fetch it again next time.
        throw IOException("Could not load speech model")
    }
    val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
    val words = ArrayList<TranscriptWord>()
    try {
        DataInputStream(pcm.inputStream().buffered(1 shl 16)).use { input ->
            var offsetSamples = 0L
            var carry = FloatArray(0)
            var done = false
            while (!done) {
                if (!isActive()) throw InterruptedException()
                val window = FloatArray(WINDOW_SAMPLES)
                carry.copyInto(window)
                var size = carry.size
                while (size < window.size) {
                    val sample = try { readShort(input) } catch (_: EOFException) { done = true; break }
                    window[size++] = sample / 32768f
                }
                if (size == 0) break
                val cut = if (done) size else quietestCut(window, size)
                for (line in String(NativeWhisper.transcribe(handle, window.copyOf(cut), threads), Charsets.UTF_8).lineSequence()) {
                    val parts = line.split('\t', limit = 3)
                    if (parts.size < 3 || parts[2].isBlank()) continue
                    val ms = offsetSamples * 1000 / WHISPER_SAMPLE_RATE
                    words.add(TranscriptWord(parts[0].toLong() + ms, parts[1].toLong() + ms, parts[2].trim()))
                }
                carry = window.copyOfRange(cut, size)
                offsetSamples += cut
            }
        }
    } finally {
        NativeWhisper.release(handle)
    }
    return words
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

/** Index in the last [CUT_SEARCH_SAMPLES] of the window where a 100 ms frame is quietest. */
private fun quietestCut(window: FloatArray, size: Int): Int {
    val frame = WHISPER_SAMPLE_RATE * ENVELOPE_FRAME_MS / 1000
    var best = size
    var bestEnergy = Double.MAX_VALUE
    var start = (size - CUT_SEARCH_SAMPLES).coerceAtLeast(0)
    while (start + frame <= size) {
        var energy = 0.0
        for (i in start until start + frame) energy += window[i] * window[i]
        if (energy < bestEnergy) { bestEnergy = energy; best = start }
        start += frame
    }
    return best
}
