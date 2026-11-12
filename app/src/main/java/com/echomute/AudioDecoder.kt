package com.echomute

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteOrder

/**
 * Decodes [source] to 16-bit little-endian mono PCM at [targetRate] Hz and returns the rate written: [targetRate],
 * or 8000 when the source is sampled below [targetRate] and cannot be decimated to it.
 */
fun decodeAudio(source: File, destination: File, targetRate: Int = 8000, onProgress: (Long, Long) -> Unit = { _, _ -> }): Int {
    val extractor = MediaExtractor()
    var codec: MediaCodec? = null
    try {
        extractor.setDataSource(source.absolutePath)
        val index = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            ?: error("No audio track found")
        extractor.selectTrack(index)
        val format = extractor.getTrackFormat(index)
        val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
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
        val info = MediaCodec.BufferInfo()
        var byteBuf = ByteArray(16384)
        BufferedOutputStream(destination.outputStream(), 65536).use { output ->
            while (!outputDone) {
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                if (!inputDone) {
                    val inputIndex = decoder.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val input = requireNotNull(decoder.getInputBuffer(inputIndex))
                        val size = extractor.readSampleData(input, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                            if (durationUs > 0) onProgress(durationUs, durationUs)
                        } else {
                            val sampleTime = extractor.sampleTime
                            decoder.queueInputBuffer(inputIndex, 0, size, sampleTime, 0)
                            if (durationUs > 0 && sampleTime >= 0) onProgress(sampleTime.coerceAtMost(durationUs), durationUs)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val decoded = decoder.outputFormat
                    val decodedRate = if (decoded.containsKey(MediaFormat.KEY_SAMPLE_RATE)) decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE) else sampleRate
                    val channels = if (decoded.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else channelCount
                    resampler = PcmResampler(decodedRate, channels, rate)
                    noOutputCount = 0
                } else if (outIndex >= 0) {
                    noOutputCount = 0
                    if (info.size > 0) {
                        val buffer = requireNotNull(decoder.getOutputBuffer(outIndex)).order(ByteOrder.LITTLE_ENDIAN)
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val samples = ShortArray(info.size / 2)
                        buffer.asShortBuffer().get(samples)
                        val converted = resampler.convert(samples)
                        if (converted.isNotEmpty()) {
                            val needed = converted.size * 2
                            if (byteBuf.size < needed) byteBuf = ByteArray(needed)
                            var idx = 0
                            for (sample in converted) {
                                val s = sample.toInt()
                                byteBuf[idx++] = (s and 255).toByte()
                                byteBuf[idx++] = ((s ushr 8) and 255).toByte()
                            }
                            output.write(byteBuf, 0, idx)
                        }
                    }
                    outputDone = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                    decoder.releaseOutputBuffer(outIndex, false)
                } else {
                    if (inputDone) {
                        noOutputCount++
                        if (noOutputCount > 50) break
                    }
                }
            }
        }
        return rate
    } finally {
        codec?.release()
        extractor.release()
    }
}
