package com.echomute

fun unpackFingerprint(value: Long) = Fingerprint((value ushr 32).toInt(), value and 0xffffffffL)

object NativeFingerprint {
    init { System.loadLibrary("echomute") }
    external fun generateFingerprints(pcmFilePath: String): LongArray
}
