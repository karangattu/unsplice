package com.echomute

/** Averages [channels] to mono and decimates from [sampleRate] to [targetRate] Hz with a box filter. */
class PcmResampler(private val sampleRate: Int, private val channels: Int, private val targetRate: Int = 8000) {
    init { require(sampleRate >= targetRate && targetRate > 0 && channels > 0) }
    private var phase = 0
    private var sum = 0L
    private var count = 0

    fun convert(input: ShortArray): ShortArray {
        require(input.size % channels == 0)
        val output = ShortArray(((input.size / channels).toLong() * targetRate / sampleRate + 1).toInt())
        var size = 0
        for (i in input.indices step channels) {
            var mono = 0L
            for (c in 0 until channels) mono += input[i + c]
            sum += mono / channels
            count++
            phase += targetRate
            if (phase >= sampleRate) {
                output[size++] = (sum / count).toShort()
                phase -= sampleRate
                sum = 0
                count = 0
            }
        }
        return output.copyOf(size)
    }
}
