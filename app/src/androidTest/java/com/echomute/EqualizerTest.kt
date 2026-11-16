package com.echomute

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Rule
import org.junit.Test

class EqualizerTest {
    @get:Rule val compose = createComposeRule()

    @Test fun barsBounceWhilePlayingAndCollapseWhenPaused() {
        var playing by mutableStateOf(true)
        compose.setContent { PlayingEqualizer(playing = playing) }

        compose.waitUntil(2_000) {
            (0 until 3).any { index ->
                compose.onNodeWithTag("equalizer-bar-$index").fetchSemanticsNode().size.height > 0
            }
        }

        compose.runOnIdle { playing = false }
        compose.waitUntil(2_000) {
            (0 until 3).all { index ->
                compose.onNodeWithTag("equalizer-bar-$index").fetchSemanticsNode().size.height == 0
            }
        }
    }
}
