// SPDX-License-Identifier: MIT
package dev.opendistress.wear.ui

/** Pure monotonic-time state for [PanicHoldView]. */
internal class HoldGestureState(
    val durationMillis: Long = DEFAULT_DURATION_MILLIS,
    val accessibilityConfirmationWindowMillis: Long = DEFAULT_ACCESSIBILITY_CONFIRMATION_WINDOW_MILLIS,
) {
    init {
        require(durationMillis > 0)
        require(accessibilityConfirmationWindowMillis > 0)
    }

    enum class Phase {
        IDLE,
        TOUCH_HOLDING,
        ACCESSIBILITY_HOLDING,
        ACCESSIBILITY_READY,
    }

    enum class AccessibilityResult {
        STARTED,
        CANCELLED,
        TRIGGERED,
    }

    data class Snapshot(
        val phase: Phase,
        val progress: Float,
    )

    private var phase = Phase.IDLE
    private var startedAtMillis = 0L

    fun startTouch(nowMillis: Long) {
        phase = Phase.TOUCH_HOLDING
        startedAtMillis = nowMillis
    }

    fun cancel(): Boolean {
        if (phase == Phase.IDLE) return false
        phase = Phase.IDLE
        startedAtMillis = 0L
        return true
    }

    fun completeTouch(nowMillis: Long): Boolean {
        if (phase != Phase.TOUCH_HOLDING || elapsed(nowMillis) < durationMillis) return false
        phase = Phase.IDLE
        startedAtMillis = 0L
        return true
    }

    fun accessibilityClick(nowMillis: Long): AccessibilityResult {
        updateAccessibilityPhase(nowMillis)
        return when (phase) {
            Phase.IDLE -> {
                phase = Phase.ACCESSIBILITY_HOLDING
                startedAtMillis = nowMillis
                AccessibilityResult.STARTED
            }
            Phase.ACCESSIBILITY_HOLDING -> {
                cancel()
                AccessibilityResult.CANCELLED
            }
            Phase.ACCESSIBILITY_READY -> {
                cancel()
                AccessibilityResult.TRIGGERED
            }
            Phase.TOUCH_HOLDING -> {
                cancel()
                AccessibilityResult.CANCELLED
            }
        }
    }

    fun snapshot(nowMillis: Long): Snapshot {
        updateAccessibilityPhase(nowMillis)
        val progress = when (phase) {
            Phase.IDLE -> 0f
            Phase.ACCESSIBILITY_READY -> 1f
            else -> (elapsed(nowMillis).toDouble() / durationMillis.toDouble())
                .coerceIn(0.0, 1.0)
                .toFloat()
        }
        return Snapshot(phase, progress)
    }

    private fun updateAccessibilityPhase(nowMillis: Long) {
        if (phase == Phase.ACCESSIBILITY_HOLDING && elapsed(nowMillis) >= durationMillis) {
            phase = Phase.ACCESSIBILITY_READY
        }
        if (
            phase == Phase.ACCESSIBILITY_READY &&
            elapsed(nowMillis) - durationMillis >= accessibilityConfirmationWindowMillis
        ) {
            cancel()
        }
    }

    private fun elapsed(nowMillis: Long): Long = (nowMillis - startedAtMillis).coerceAtLeast(0L)

    companion object {
        const val DEFAULT_DURATION_MILLIS = 2_500L
        const val DEFAULT_ACCESSIBILITY_CONFIRMATION_WINDOW_MILLIS = 5_000L
    }
}
