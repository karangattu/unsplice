package com.echomute

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class DecodedAudioAnalysis(
    val durationMs: Long?,
    val envelope: IntArray,
    val fingerprints: LongArray
)

fun probeAudioDurationMs(source: File): Long? {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(source.absolutePath)
        val index = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        }
        if (index != null) {
            val format = extractor.getTrackFormat(index)
            if (format.containsKey(MediaFormat.KEY_DURATION)) {
                val us = format.getLong(MediaFormat.KEY_DURATION)
                if (us > 0L) return us / 1000L
            }
        }
    } catch (_: Exception) {
    } finally {
        extractor.release()
    }
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(source.absolutePath)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    } catch (_: Exception) {
        null
    } finally {
        retriever.release()
    }
}

private inline fun pumpDecodedAudio(
    source: File,
    onProgress: (Long, Long) -> Unit,
    onFormat: (sampleRate: Int, channels: Int, durationUs: Long) -> Unit,
    onFormatChanged: (sampleRate: Int, channels: Int) -> Unit,
    onBuffer: (buffer: ByteBuffer, offset: Int, size: Int) -> Unit
) {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        extractor.setDataSource(source.absolutePath)
        val index = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: error("No audio track found")
        extractor.selectTrack(index)
        val format = extractor.getTrackFormat(index)
        var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        var channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        onFormat(sampleRate, channelCount, durationUs)

        val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
        codec = decoder
        decoder.configure(format, null, null, 0)
        decoder.start()

        var inputDone = false
        var outputDone = false
        var noOutputCount = 0
        var lastProgressUs = -1L
        val info = MediaCodec.BufferInfo()

        while (!outputDone) {
            if (Thread.currentThread().isInterrupted) throw InterruptedException()
            var inputQueued = false

            while (!inputDone) {
                val inputIndex = decoder.dequeueInputBuffer(0)
                if (inputIndex < 0) break
                val input = requireNotNull(decoder.getInputBuffer(inputIndex))
                val size = extractor.readSampleData(input, 0)
                if (size < 0) {
                    decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    inputDone = true
                    break
                } else {
                    inputQueued = true
                    val sampleTime = extractor.sampleTime
                    decoder.queueInputBuffer(inputIndex, 0, size, sampleTime, 0)
                    if (durationUs > 0 && sampleTime >= 0) {
                        val delta = sampleTime - lastProgressUs
                        if (lastProgressUs < 0 || delta >= 250_000L || delta < 0) {
                            lastProgressUs = sampleTime
                            onProgress(sampleTime.coerceAtMost(durationUs), durationUs)
                        }
                    }
                    extractor.advance()
                }
            }

            var timeoutUs = if (inputQueued) 0L else 1000L
            while (!outputDone) {
                val outIndex = decoder.dequeueOutputBuffer(info, timeoutUs)
                if (outIndex >= 0) {
                    noOutputCount = 0
                    if (info.size > 0) {
                        val buffer = requireNotNull(decoder.getOutputBuffer(outIndex))
                        onBuffer(buffer, info.offset, info.size)
                    }
                    outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    decoder.releaseOutputBuffer(outIndex, false)
                    timeoutUs = 0L
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    noOutputCount = 0
                    val decoded = decoder.outputFormat
                    sampleRate = if (decoded.containsKey(MediaFormat.KEY_SAMPLE_RATE)) decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE) else sampleRate
                    channelCount = if (decoded.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else channelCount
                    onFormatChanged(sampleRate, channelCount)
                    timeoutUs = 0L
                } else {
                    if (inputDone) {
                        noOutputCount++
                        if (noOutputCount > 50) break
                    }
                    break
                }
            }
        }
        if (durationUs > 0) {
            onProgress(durationUs, durationUs)
        }
    } finally {
        codec?.release()
        extractor.release()
    }
}

fun decodeAudio(source: File, destination: File, targetRate: Int = 8000, onProgress: (Long, Long) -> Unit = { _, _ -> }): Int {
    var rate = targetRate
    var currentSampleRate = 44100
    var resampler: PcmResampler? = null
    var sampleBuf = ShortArray(16384)
    var byteBuf = ByteArray(32768)

    BufferedOutputStream(destination.outputStream(), 65536).use { output ->
        pumpDecodedAudio(
            source = source,
            onProgress = onProgress,
            onFormat = { sampleRate, channels, _ ->
                currentSampleRate = sampleRate
                rate = if (sampleRate >= targetRate) targetRate else 8000
                resampler = PcmResampler(sampleRate, channels, rate)
            },
            onFormatChanged = { sampleRate, channels ->
                currentSampleRate = sampleRate
                resampler = PcmResampler(sampleRate, channels, rate)
            },
            onBuffer = { buffer, offset, size ->
                val le = buffer.order(ByteOrder.LITTLE_ENDIAN)
                le.position(offset)
                le.limit(offset + size)
                val numSamples = size / 2
                if (sampleBuf.size < numSamples) sampleBuf = ShortArray(numSamples)
                le.asShortBuffer().get(sampleBuf, 0, numSamples)
                val maxOutBytes = ((numSamples.toLong() * rate / currentSampleRate + 2) * 2).toInt()
                if (byteBuf.size < maxOutBytes) byteBuf = ByteArray(maxOf(byteBuf.size * 2, maxOutBytes))
                val written = resampler!!.convertToBytes(sampleBuf, numSamples, byteBuf, 0)
                if (written > 0) output.write(byteBuf, 0, written)
            }
        )
    }
    return rate
}

fun decodeAudioForAnalysis(
    source: File,
    targetRate: Int = 8000,
    envelopeRate: Int = 0,
    fingerprint: Boolean = true,
    onProgress: (Long, Long) -> Unit = { _, _ -> }
): DecodedAudioAnalysis {
    var handle = 0L
    var durationMs: Long? = null
    var fallbackBytes = ByteArray(0)
    try {
        pumpDecodedAudio(
            source = source,
            onProgress = onProgress,
            onFormat = { sampleRate, channels, durationUs ->
                durationMs = (durationUs / 1000L).takeIf { it > 0L }
                val rate = if (sampleRate >= targetRate) targetRate else 8000
                handle = NativeFingerprint.createAnalyzer(sampleRate, channels, rate, envelopeRate, fingerprint)
            },
            onFormatChanged = { sampleRate, channels ->
                if (handle != 0L) NativeFingerprint.configureAnalyzer(handle, sampleRate, channels)
            },
            onBuffer = { buffer, offset, size ->
                if (handle == 0L) return@pumpDecodedAudio
                if (buffer.isDirect) {
                    NativeFingerprint.feedPcmBuffer(handle, buffer, offset, size)
                } else {
                    if (fallbackBytes.size < size) fallbackBytes = ByteArray(maxOf(fallbackBytes.size * 2, size))
                    buffer.limit(offset + size)
                    buffer.position(offset)
                    buffer.get(fallbackBytes, 0, size)
                    NativeFingerprint.feedPcmBytes(handle, fallbackBytes, 0, size)
                }
            }
        )
        val fingerprints = if (handle != 0L && fingerprint) NativeFingerprint.finishAnalyzer(handle) else LongArray(0)
        val envelope = if (handle != 0L && envelopeRate > 0) NativeFingerprint.analyzerEnvelope(handle) else IntArray(0)
        return DecodedAudioAnalysis(durationMs, envelope, fingerprints)
    } finally {
        if (handle != 0L) NativeFingerprint.destroyAnalyzer(handle)
    }
}
