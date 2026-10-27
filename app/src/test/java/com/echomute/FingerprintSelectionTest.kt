package com.echomute

import org.junit.Assert.assertEquals
import org.junit.Test

class FingerprintSelectionTest {
    @Test fun keepsDistinctHashesPerSecondAndLimitsRepetition() {
        val input = listOf(Fingerprint(1, 100), Fingerprint(1, 200), Fingerprint(2, 300), Fingerprint(3, 400), Fingerprint(4, 1100))
        assertEquals(listOf(Fingerprint(1, 100), Fingerprint(2, 300), Fingerprint(4, 1100)), selectFingerprints(input, 2))
    }
}
