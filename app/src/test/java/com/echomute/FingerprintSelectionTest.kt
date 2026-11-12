package com.echomute

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FingerprintSelectionTest {
    @Test fun keepsDistinctHashesPerSecondAndLimitsRepetition() {
        val input = listOf(Fingerprint(1, 100), Fingerprint(1, 200), Fingerprint(2, 300), Fingerprint(3, 400), Fingerprint(4, 1100))
        assertEquals(listOf(Fingerprint(1, 100), Fingerprint(2, 300), Fingerprint(4, 1100)), selectFingerprints(input, 2))
    }

    @Test fun packedSelectionMatchesListSelection() {
        val input = longArrayOf(
            packFingerprint(1, 100),
            packFingerprint(1, 200),
            packFingerprint(2, 300),
            packFingerprint(3, 400),
            packFingerprint(4, 1100)
        )
        val expected = longArrayOf(
            packFingerprint(1, 100),
            packFingerprint(2, 300),
            packFingerprint(4, 1100)
        )
        assertArrayEquals(expected, selectPackedFingerprints(input, 2))
    }
}
