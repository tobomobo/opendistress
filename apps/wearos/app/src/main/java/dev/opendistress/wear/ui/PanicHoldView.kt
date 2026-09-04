// SPDX-License-Identifier: MIT
package dev.opendistress.wear.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.min

/**
 * A full-screen panic control that requires an uninterrupted 2.5 second hold.
 *
 * This class recognizes intent only. The owner persists and transmits an event from
 * [Listener.onTriggerConfirmed]. Haptics are likewise delegated so tests and product
 * policy can select the actual effect without coupling it to drawing code.
 *
 * TalkBack's click action uses a deliberate two-step alternative: activate once,
 * wait for the ready announcement, then activate again. It never sends on one tap.
 */
class PanicHoldView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    enum class Label {
        READY,
        HOLDING,
        RELEASE_CANCELS,
        ACCESSIBILITY_WAIT,
        ACCESSIBILITY_CONFIRM,
        CONFIRMED,
    }

    enum class HapticCue {
        HOLD_STARTED,
        CANCELLED,
        READY_TO_CONFIRM,
        CONFIRMED,
    }

    interface Listener {
        fun onLabelChanged(label: Label) = Unit
        fun onHapticCue(cue: HapticCue) = Unit
        fun onTriggerConfirmed()
    }

    var listener: Listener? = null
        set(value) {
            field = value
            value?.onLabelChanged(currentLabel)
        }

    private val gesture = HoldGestureState()
    private val progressBounds = RectF()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(55, 59, 65)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 73, 84)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(93, 15, 24)
        style = Paint.Style.FILL
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(194, 197, 204)
        textAlign = Paint.Align.CENTER
    }

    private var touchActive = false
    private var currentLabel = Label.READY
    private var accessibilityReadyAnnounced = false
    private var confirmed = false

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Emergency alert. Hold for two and a half seconds."
    }

    override fun getAccessibilityClassName(): CharSequence = android.widget.Button::class.java.name

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.stateDescription = when (gesture.snapshot(SystemClock.elapsedRealtime()).phase) {
            HoldGestureState.Phase.ACCESSIBILITY_HOLDING -> "Waiting before confirmation"
            HoldGestureState.Phase.ACCESSIBILITY_READY -> "Ready for final confirmation"
            HoldGestureState.Phase.TOUCH_HOLDING -> "Hold in progress"
            HoldGestureState.Phase.IDLE -> "Not activated"
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        when (gesture.accessibilityClick(SystemClock.elapsedRealtime())) {
            HoldGestureState.AccessibilityResult.STARTED -> {
                accessibilityReadyAnnounced = false
                setLabel(Label.ACCESSIBILITY_WAIT)
                listener?.onHapticCue(HapticCue.HOLD_STARTED)
                announceForAccessibility("Confirmation delay started. Wait, then activate again to send. Activate now to cancel.")
                postInvalidateOnAnimation()
            }
            HoldGestureState.AccessibilityResult.CANCELLED -> cancelPresentation()
            HoldGestureState.AccessibilityResult.TRIGGERED -> confirm()
        }
        return true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val now = SystemClock.elapsedRealtime()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!isEnabled || !isInsideControl(event.x, event.y)) return false
                touchActive = true
                gesture.startTouch(now)
                parent?.requestDisallowInterceptTouchEvent(true)
                setLabel(Label.HOLDING)
                listener?.onHapticCue(HapticCue.HOLD_STARTED)
                postInvalidateOnAnimation()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchActive) return false
                if (!isInsideControl(event.x, event.y, MOVE_SLOP_FACTOR)) {
                    cancelTouch()
                } else {
                    setLabel(Label.RELEASE_CANCELS)
                    postInvalidateOnAnimation()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!touchActive) return false
                // Report a semantic click without invoking the accessibility two-step path.
                super.performClick()
                if (gesture.completeTouch(now)) {
                    touchActive = false
                    confirm()
                } else {
                    cancelTouch()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (touchActive) cancelTouch()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val centerX = width / 2f
        val centerY = height / 2f
        val stroke = size * 0.055f
        val ringRadius = size * 0.43f
        val buttonRadius = size * 0.30f
        val snapshot = gesture.snapshot(SystemClock.elapsedRealtime())
        val displayProgress = if (confirmed) 1f else snapshot.progress

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        trackPaint.strokeWidth = stroke
        progressPaint.strokeWidth = stroke
        progressBounds.set(
            centerX - ringRadius,
            centerY - ringRadius,
            centerX + ringRadius,
            centerY + ringRadius,
        )
        canvas.drawCircle(centerX, centerY, ringRadius, trackPaint)
        // Android angles begin at three o'clock. 90 degrees is six o'clock.
        val halfSweep = displayProgress * 180f
        canvas.drawArc(progressBounds, 90f, halfSweep, false, progressPaint)
        canvas.drawArc(progressBounds, 90f, -halfSweep, false, progressPaint)

        val active = confirmed || snapshot.phase != HoldGestureState.Phase.IDLE
        buttonPaint.color = if (active) Color.rgb(127, 17, 29) else Color.rgb(82, 20, 27)
        canvas.drawCircle(centerX, centerY, buttonRadius, buttonPaint)

        titlePaint.textSize = size * 0.095f
        hintPaint.textSize = size * 0.052f
        val title = if (confirmed) "CONFIRMED" else when (snapshot.phase) {
            HoldGestureState.Phase.IDLE -> "HOLD"
            HoldGestureState.Phase.TOUCH_HOLDING -> "KEEP HOLDING"
            HoldGestureState.Phase.ACCESSIBILITY_HOLDING -> "WAIT"
            HoldGestureState.Phase.ACCESSIBILITY_READY -> "CONFIRM"
        }
        canvas.drawText(title, centerX, centerY - size * 0.015f, titlePaint)
        val hint = if (confirmed) "SENDING" else when (snapshot.phase) {
            HoldGestureState.Phase.IDLE -> "2.5 SECONDS"
            HoldGestureState.Phase.TOUCH_HOLDING -> {
                val remainingTenths = ceil((1f - snapshot.progress) * 25f).toInt().coerceAtLeast(0)
                "${remainingTenths / 10}.${remainingTenths % 10}s · RELEASE CANCELS"
            }
            HoldGestureState.Phase.ACCESSIBILITY_HOLDING -> "ACTIVATE NOW TO CANCEL"
            HoldGestureState.Phase.ACCESSIBILITY_READY -> "ACTIVATE TO SEND"
        }
        canvas.drawText(hint, centerX, centerY + size * 0.075f, hintPaint)

        if (gesture.completeTouch(SystemClock.elapsedRealtime())) {
            touchActive = false
            confirm()
            return
        }
        if (snapshot.phase == HoldGestureState.Phase.ACCESSIBILITY_READY && !accessibilityReadyAnnounced) {
            accessibilityReadyAnnounced = true
            setLabel(Label.ACCESSIBILITY_CONFIRM)
            listener?.onHapticCue(HapticCue.READY_TO_CONFIRM)
            announceForAccessibility("Ready. Activate again to send the emergency alert.")
        }
        if (snapshot.phase != HoldGestureState.Phase.IDLE) postInvalidateOnAnimation()
    }

    private fun isInsideControl(x: Float, y: Float, radiusFactor: Float = 0.47f): Boolean {
        val radius = min(width, height) * radiusFactor
        return hypot(x - width / 2f, y - height / 2f) <= radius
    }

    private fun cancelTouch() {
        touchActive = false
        parent?.requestDisallowInterceptTouchEvent(false)
        if (gesture.cancel()) cancelPresentation()
    }

    private fun cancelPresentation() {
        accessibilityReadyAnnounced = false
        setLabel(Label.READY)
        listener?.onHapticCue(HapticCue.CANCELLED)
        announceForAccessibility("Alert cancelled.")
        invalidate()
    }

    private fun confirm() {
        if (confirmed) return
        confirmed = true
        isEnabled = false
        parent?.requestDisallowInterceptTouchEvent(false)
        accessibilityReadyAnnounced = false
        setLabel(Label.CONFIRMED)
        listener?.onHapticCue(HapticCue.CONFIRMED)
        announceForAccessibility("Emergency alert confirmed.")
        listener?.onTriggerConfirmed()
        invalidate()
    }

    /** Allows the owner to recover after a local persistence failure without recreating the view. */
    fun resetToReady() {
        gesture.cancel()
        touchActive = false
        parent?.requestDisallowInterceptTouchEvent(false)
        confirmed = false
        isEnabled = true
        accessibilityReadyAnnounced = false
        setLabel(Label.READY)
        invalidate()
    }

    private fun setLabel(label: Label) {
        if (currentLabel == label) return
        currentLabel = label
        listener?.onLabelChanged(label)
    }

    companion object {
        private const val MOVE_SLOP_FACTOR = 0.53f
    }
}
