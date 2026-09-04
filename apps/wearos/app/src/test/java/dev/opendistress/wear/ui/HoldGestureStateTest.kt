// SPDX-License-Identifier: MIT
package dev.opendistress.wear.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HoldGestureStateTest {
    @Test
    fun shortTouchCannotTrigger() {
        val state = HoldGestureState()
        state.startTouch(1_000L)

        assertFalse(state.completeTouch(3_499L))
        assertTrue(state.cancel())
        assertEquals(HoldGestureState.Phase.IDLE, state.snapshot(10_000L).phase)
    }

    @Test
    fun uninterruptedTouchTriggersAtThreshold() {
        val state = HoldGestureState()
        state.startTouch(1_000L)

        assertTrue(state.completeTouch(3_500L))
        assertFalse(state.completeTouch(6_000L))
    }

    @Test
    fun elapsedTimeCannotMoveBackward() {
        val state = HoldGestureState()
        state.startTouch(10_000L)

        assertEquals(0f, state.snapshot(9_000L).progress)
        assertFalse(state.completeTouch(9_000L))
    }

    @Test
    fun accessibilityRequiresWaitAndSecondActivation() {
        val state = HoldGestureState()

        assertEquals(
            HoldGestureState.AccessibilityResult.STARTED,
            state.accessibilityClick(100L),
        )
        assertEquals(HoldGestureState.Phase.ACCESSIBILITY_READY, state.snapshot(2_600L).phase)
        assertEquals(
            HoldGestureState.AccessibilityResult.TRIGGERED,
            state.accessibilityClick(2_600L),
        )
    }

    @Test
    fun earlySecondAccessibilityActivationCancels() {
        val state = HoldGestureState()
        state.accessibilityClick(100L)

        assertEquals(
            HoldGestureState.AccessibilityResult.CANCELLED,
            state.accessibilityClick(2_599L),
        )
        assertEquals(HoldGestureState.Phase.IDLE, state.snapshot(9_000L).phase)
    }

    @Test
    fun accessibilityConfirmationExpiresAndOneLateActivationCannotTrigger() {
        val state = HoldGestureState()
        state.accessibilityClick(100L)

        assertEquals(HoldGestureState.Phase.ACCESSIBILITY_READY, state.snapshot(2_600L).phase)
        assertEquals(HoldGestureState.Phase.IDLE, state.snapshot(7_600L).phase)
        assertEquals(
            HoldGestureState.AccessibilityResult.STARTED,
            state.accessibilityClick(7_600L),
        )
    }
}
