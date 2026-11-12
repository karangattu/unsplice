package com.echomute

import java.nio.ByteBuffer

fun unpackFingerprint(value: Long) = Fingerprint((value ushr 32).toInt(), value and 0xffffffffL)

fun packFingerprint(hash: Int, timeMs: Long): Long = (hash.toLong() shl 32) or (timeMs and 0xffffffffL)

fun packedHash(packed: Long): Int = (packed ushr 32).toInt()

fun packedTimeMs(packed: Long): Long = packed and 0xffffffffL

object NativeFingerprint {
    init { System.loadLibrary("echomute") }
    external fun generateFingerprints(pcmFilePath: String): LongArray
    external fun generateFingerprintsFromBytes(bytes: ByteArray, length: Int): LongArray
    external fun createAnalyzer(sampleRate: Int, channels: Int, targetRate: Int, envelopeRate: Int, enableFingerprint: Boolean): Long
    external fun configureAnalyzer(handle: Long, sampleRate: Int, channels: Int)
    external fun feedPcmBuffer(handle: Long, buffer: ByteBuffer, offset: Int, size: Int)
    external fun feedPcmBytes(handle: Long, bytes: ByteArray, offset: Int, size: Int)
    external fun finishAnalyzer(handle: Long): LongArray
    external fun analyzerEnvelope(handle: Long): IntArray
    external fun destroyAnalyzer(handle: Long)
}
