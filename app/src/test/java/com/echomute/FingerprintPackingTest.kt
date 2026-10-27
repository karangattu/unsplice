package com.echomute
import org.junit.Assert.assertEquals
import org.junit.Test
class FingerprintPackingTest {
    @Test fun decodesUnsignedHashAndTimestamp() {
        assertEquals(Fingerprint(-1, 4_000_000_000L), unpackFingerprint(-294967296L))
    }
}
