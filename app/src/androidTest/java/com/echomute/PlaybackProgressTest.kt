package com.echomute

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class PlaybackProgressTest {
    @get:Rule val compose = createComposeRule()

    @Test fun seekBarShowsTimesAndSeeksToRequestedPosition() {
        var position by mutableLongStateOf(30_000L)
        compose.setContent {
            PlaybackProgress(position, 120_000L, emptyList()) { position = it }
        }
        compose.onNodeWithText("00:30").assertExists()
        compose.onNodeWithText("02:00").assertExists()
        compose.onNodeWithContentDescription("Playback position")
            .performSemanticsAction(SemanticsActions.SetProgress) { it(60_000f) }
        compose.runOnIdle { assertEquals(60_000L, position) }
        compose.onNodeWithText("01:00").assertExists()
    }

    @Test fun playbackUpdatesDoNotMoveThumbDuringScrubbing() {
        var position by mutableLongStateOf(30_000L)
        var seek = -1L
        compose.setContent { PlaybackProgress(position, 120_000L, emptyList()) { seek = it } }
        val slider = compose.onNodeWithContentDescription("Playback position")
        slider.performTouchInput {
            down(Offset(width * 0.25f, center.y))
            moveTo(Offset(width * 0.75f, center.y))
        }
        compose.runOnIdle { position = 10_000L }
        val preview = slider.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
        assertTrue("Thumb should stay at the selected position", preview > 40_000f)
        assertEquals(-1L, seek)
        slider.performTouchInput { up() }
        compose.runOnIdle { assertTrue("Release should seek to the selected position", seek > 40_000L) }
    }
}
