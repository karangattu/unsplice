package com.echomute

fun selectFingerprints(input: List<Fingerprint>, limitPerSecond: Int = 8): List<Fingerprint> {
    require(limitPerSecond > 0)
    var second = Long.MIN_VALUE
    val hashes = mutableSetOf<Int>()
    return input.filter { point ->
        val currentSecond = point.timeMs / 1000
        if (currentSecond != second) { second = currentSecond; hashes.clear() }
        hashes.size < limitPerSecond && hashes.add(point.hash)
    }
}
