package com.echomute

class PcmResampler(private val sampleRate: Int, private val channels: Int, private val targetRate: Int = 8000) {
    init { require(sampleRate >= targetRate && targetRate > 0 && channels > 0) }
    private var phase = 0
    private var sum = 0L
    private var count = 0

    fun convert(input: ShortArray): ShortArray {
        require(input.size % channels == 0)
        val output = ShortArray(((input.size / channels).toLong() * targetRate / sampleRate + 1).toInt())
        var size = 0
        when (channels) {
            1 -> {
                for (i in input.indices) {
                    sum += input[i]
                    count++
                    phase += targetRate
                    if (phase >= sampleRate) {
                        output[size++] = (sum / count).toShort()
                        phase -= sampleRate
                        sum = 0L
                        count = 0
                    }
                }
            }
            2 -> {
                for (i in input.indices step 2) {
                    sum += (input[i].toInt() + input[i + 1].toInt()) / 2
                    count++
                    phase += targetRate
                    if (phase >= sampleRate) {
                        output[size++] = (sum / count).toShort()
                        phase -= sampleRate
                        sum = 0L
                        count = 0
                    }
                }
            }
            else -> {
                for (i in input.indices step channels) {
                    var mono = 0L
                    for (c in 0 until channels) mono += input[i + c]
                    sum += mono / channels
                    count++
                    phase += targetRate
                    if (phase >= sampleRate) {
                        output[size++] = (sum / count).toShort()
                        phase -= sampleRate
                        sum = 0L
                        count = 0
                    }
                }
            }
        }
        return if (size == output.size) output else output.copyOf(size)
    }

    fun convertToBytes(input: ShortArray, length: Int, out: ByteArray, offset: Int = 0): Int {
        require(length % channels == 0)
        var outIdx = offset
        when (channels) {
            1 -> {
                for (i in 0 until length) {
                    sum += input[i]
                    count++
                    phase += targetRate
                    if (phase >= sampleRate) {
                        val s = (sum / count).toInt()
                        out[outIdx++] = (s and 0xFF).toByte()
                        out[outIdx++] = ((s ushr 8) and 0xFF).toByte()
                        phase -= sampleRate
                        sum = 0L
                        count = 0
                    }
                }
            }
            2 -> {
                for (i in 0 until length step 2) {
                    sum += (input[i].toInt() + input[i + 1].toInt()) / 2
                    count++
                    phase += targetRate
                    if (phase >= sampleRate) {
                        val s = (sum / count).toInt()
                        out[outIdx++] = (s and 0xFF).toByte()
                        out[outIdx++] = ((s ushr 8) and 0xFF).toByte()
                        phase -= sampleRate
                        sum = 0L
                        count = 0
                    }
                }
            }
            else -> {
                for (i in 0 until length step channels) {
                    var mono = 0L
                    for (c in 0 until channels) mono += input[i + c]
                    sum += mono / channels
                    count++
                    phase += targetRate
                    if (phase >= sampleRate) {
                        val s = (sum / count).toInt()
                        out[outIdx++] = (s and 0xFF).toByte()
                        out[outIdx++] = ((s ushr 8) and 0xFF).toByte()
                        phase -= sampleRate
                        sum = 0L
                        count = 0
                    }
                }
            }
        }
        return outIdx - offset
    }
}
