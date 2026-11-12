package com.echomute

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteOrder

fun decodeAudio(source: File, destination: File, targetRate: Int = 8000, onProgress: (Long, Long) -> Unit = { _, _ -> }): Int {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        extractor.setDataSource(source.absolutePath)
        val index = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: error("No audio track found")
        extractor.selectTrack(index)
        val format = extractor.getTrackFormat(index)
        var sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
        val rate = if (sampleRate >= targetRate) targetRate else 8000
        var resampler = PcmResampler(sampleRate, channelCount, rate)

        val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
        codec = decoder
        decoder.configure(format, null, null, 0)
        decoder.start()

        var inputDone = false
        var outputDone = false
        var noOutputCount = 0
        var lastProgressUs = -1L
        val info = MediaCodec.BufferInfo()

        var sampleBuf = ShortArray(16384)
        var byteBuf = ByteArray(32768)

        BufferedOutputStream(destination.outputStream(), 65536).use { output ->
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
                            val buffer = requireNotNull(decoder.getOutputBuffer(outIndex)).order(ByteOrder.LITTLE_ENDIAN)
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val numSamples = info.size / 2
                            if (sampleBuf.size < numSamples) {
                                sampleBuf = ShortArray(numSamples)
                            }
                            buffer.asShortBuffer().get(sampleBuf, 0, numSamples)
                            val maxOutBytes = ((numSamples.toLong() * rate / sampleRate + 2) * 2).toInt()
                            if (byteBuf.size < maxOutBytes) {
                                byteBuf = ByteArray(maxOf(byteBuf.size * 2, maxOutBytes))
                            }
                            val written = resampler.convertToBytes(sampleBuf, numSamples, byteBuf, 0)
                            if (written > 0) {
                                output.write(byteBuf, 0, written)
                            }
                        }
                        outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        decoder.releaseOutputBuffer(outIndex, false)
                        timeoutUs = 0L
                    } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        noOutputCount = 0
                        val decoded = decoder.outputFormat
                        sampleRate = if (decoded.containsKey(MediaFormat.KEY_SAMPLE_RATE)) decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE) else sampleRate
                        val channels = if (decoded.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else channelCount
                        resampler = PcmResampler(sampleRate, channels, rate)
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
        }
        if (durationUs > 0) {
            onProgress(durationUs, durationUs)
        }
        return rate
    } finally {
        codec?.release()
        extractor.release()
    }
}
