package com.echomute

import java.io.DataInputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EpisodeProcessorTest {
    @Test fun packedFingerprintsRoundTripAndMatchTwelveByteBinaryFormat() {
        val file = File.createTempFile("fp-roundtrip", ".bin")
        try {
            val expected = longArrayOf(
                packFingerprint(-1, 0L),
                packFingerprint(123456, 45_128L),
                packFingerprint(Int.MAX_VALUE, 3_600_999L)
            )
            writePackedFingerprints(file, expected)
            assertEquals(expected.size * 12L, file.length())
            assertArrayEquals(expected, readPackedFingerprints(file))

            val decoded = DataInputStream(file.inputStream().buffered()).use { input ->
                List(expected.size) { Fingerprint(input.readInt(), input.readLong()) }
            }
            assertEquals(
                listOf(
                    Fingerprint(-1, 0L),
                    Fingerprint(123456, 45_128L),
                    Fingerprint(Int.MAX_VALUE, 3_600_999L)
                ),
                decoded
            )
        } finally {
            file.delete()
        }
    }

    @Test fun candidateSelectionPrioritizesSamePodcastAndBoundsLibrarySize() {
        val current = Episode("cur", "pod-a", "Current", "https://example.com/cur.mp3", publishedAt = 1000L)
        val sameShow = (1..30).map { i ->
            Episode("a-$i", "pod-a", "A$i", "https://example.com/a$i.mp3", fingerprintPath = "/tmp/a$i.bin", publishedAt = i.toLong())
        }
        val otherShow = (1..25).map { i ->
            Episode("b-$i", "pod-b", "B$i", "https://example.com/b$i.mp3", fingerprintPath = "/tmp/b$i.bin", publishedAt = (i * 10).toLong())
        }
        val noFp = Episode("no-fp", "pod-a", "NoFP", "https://example.com/nofp.mp3", fingerprintPath = null, publishedAt = 9999L)

        val candidates = selectComparisonCandidates(current, sameShow + otherShow + listOf(current, noFp))
        assertEquals(40, candidates.size)
        assertEquals((30 downTo 7).map { "a-$it" }, candidates.take(24).map { it.id })
        assertEquals((25 downTo 10).map { "b-$it" }, candidates.drop(24).map { it.id })
    }
}
