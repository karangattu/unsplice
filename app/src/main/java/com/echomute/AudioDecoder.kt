package com.echomute

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.BufferedOutputStream
import java.io.File
import java.nio.ByteOrder

fun decodeAudio(source: File, destination: File) {
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
        var resampler = PcmResampler(sampleRate, channelCount)

        val decoder = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
        codec = decoder
        decoder.configure(format, null, null, 0)
        decoder.start()
        var inputDone = false
        var outputDone = false
        var noOutputCount = 0
        val info = MediaCodec.BufferInfo()
        BufferedOutputStream(destination.outputStream()).use { output ->
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
                        } else {
                            decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val decoded = decoder.outputFormat
                    val rate = if (decoded.containsKey(MediaFormat.KEY_SAMPLE_RATE)) decoded.getInteger(MediaFormat.KEY_SAMPLE_RATE) else sampleRate
                    val channels = if (decoded.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) decoded.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else channelCount
                    resampler = PcmResampler(rate, channels)
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
                            val byteBuf = ByteArray(converted.size * 2)
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
    } finally {
        codec?.release()
        extractor.release()
    }
}
