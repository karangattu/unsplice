package com.echomute

fun selectFingerprints(input: List<Fingerprint>, limitPerSecond: Int = 8): List<Fingerprint> {
    require(limitPerSecond > 0)
    var second = Long.MIN_VALUE
    val hashes = IntArray(limitPerSecond)
    var count = 0
    val out = ArrayList<Fingerprint>(minOf(input.size, limitPerSecond * 64))
    for (point in input) {
        val currentSecond = point.timeMs / 1000
        if (currentSecond != second) {
            second = currentSecond
            count = 0
        }
        if (count >= limitPerSecond) continue
        var seen = false
        for (i in 0 until count) {
            if (hashes[i] == point.hash) { seen = true; break }
        }
        if (!seen) {
            hashes[count++] = point.hash
            out.add(point)
        }
    }
    return out
}

fun selectPackedFingerprints(input: LongArray, limitPerSecond: Int = 8): LongArray {
    require(limitPerSecond > 0)
    if (input.isEmpty()) return LongArray(0)
    var second = Long.MIN_VALUE
    val hashes = IntArray(limitPerSecond)
    var count = 0
    val out = LongArray(input.size)
    var outSize = 0
    for (p in input) {
        val currentSecond = (p and 0xffffffffL) / 1000L
        if (currentSecond != second) {
            second = currentSecond
            count = 0
        }
        if (count >= limitPerSecond) continue
        val hash = (p ushr 32).toInt()
        var seen = false
        for (i in 0 until count) {
            if (hashes[i] == hash) { seen = true; break }
        }
        if (!seen) {
            hashes[count++] = hash
            out[outSize++] = p
        }
    }
    return if (outSize == out.size) out else out.copyOf(outSize)
}
