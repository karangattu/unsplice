package com.echomute

import org.junit.Assert.*
import org.junit.Test

class AnalysisPolicyTest {
    @Test fun defersOnlyWhenPlayingOnBattery() {
        assertTrue(shouldDeferAnalysis(charging = false, audioPlaying = true))
        assertFalse(shouldDeferAnalysis(charging = true, audioPlaying = true))
        assertFalse(shouldDeferAnalysis(charging = false, audioPlaying = false))
    }
}
