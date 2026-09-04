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

    enum class Purpose {
        TRIGGER_TEST,
        RESET_TEST,
    }

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

    /** RESET_TEST only confirms reset intent; the owner remains responsible for the state transition. */
    var purpose: Purpose = Purpose.TRIGGER_TEST
        set(value) {
            field = value
            updateContentDescription()
            invalidate()
        }

    private val gesture = HoldGestureState()
    private val progressBounds = RectF()
    private val modeBounds = RectF()
    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(67, 62, 68)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 180, 171)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(63, 23, 27)
        style = Paint.Style.FILL
    }
    private val modePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(43, 40, 45)
        style = Paint.Style.FILL
    }
    private val modeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(211, 196, 198)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        letterSpacing = 0.12f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 248, 247)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(211, 196, 198)
        textAlign = Paint.Align.CENTER
    }
    private val numeralPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 218, 214)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        fontFeatureSettings = "tnum"
    }

    private var touchActive = false
    private var currentLabel = Label.READY
    private var accessibilityReadyAnnounced = false
    private var confirmed = false

    init {
        isClickable = true
        isFocusable = true
        updateContentDescription()
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
                announceForAccessibility(
                    "${actionLabel()} confirmation delay started. Wait, then activate again. Activate now to cancel.",
                )
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
        val stroke = size * 0.036f
        val ringRadius = size * 0.445f
        val snapshot = gesture.snapshot(SystemClock.elapsedRealtime())
        val displayProgress = if (confirmed) 1f else snapshot.progress
        val active = confirmed || snapshot.phase != HoldGestureState.Phase.IDLE
        val buttonRadius = size * if (active) 0.302f else 0.315f

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

        buttonPaint.color = when {
            purpose == Purpose.RESET_TEST && active -> Color.rgb(91, 69, 0)
            purpose == Purpose.RESET_TEST -> Color.rgb(65, 52, 12)
            active -> Color.rgb(103, 25, 33)
            else -> Color.rgb(63, 23, 27)
        }
        progressPaint.color = if (purpose == Purpose.RESET_TEST) {
            Color.rgb(234, 213, 103)
        } else {
            Color.rgb(255, 180, 171)
        }
        canvas.drawCircle(centerX, centerY, buttonRadius, buttonPaint)

        val modeWidth = size * if (purpose == Purpose.TRIGGER_TEST) 0.24f else 0.28f
        val modeHeight = size * 0.072f
        modeBounds.set(
            centerX - modeWidth / 2f,
            height * 0.115f,
            centerX + modeWidth / 2f,
            height * 0.115f + modeHeight,
        )
        canvas.drawRoundRect(modeBounds, modeHeight / 2f, modeHeight / 2f, modePaint)
        modeTextPaint.textSize = size * 0.031f
        canvas.drawText(
            if (purpose == Purpose.TRIGGER_TEST) "TEST" else "RESET",
            centerX,
            modeBounds.centerY() - (modeTextPaint.ascent() + modeTextPaint.descent()) / 2f,
            modeTextPaint,
        )

        when {
            confirmed -> {
                val title = confirmedTitle()
                fitText(titlePaint, title, size * 0.078f, size * 0.058f, size * 0.58f)
                canvas.drawText(title, centerX, centerY - size * 0.012f, titlePaint)
                hintPaint.textSize = size * 0.040f
                canvas.drawText(confirmedHint(), centerX, centerY + size * 0.085f, hintPaint)
            }
            snapshot.phase == HoldGestureState.Phase.IDLE -> {
                titlePaint.textSize = size * 0.112f
                canvas.drawText(readyTitle(), centerX, centerY - size * 0.005f, titlePaint)
                hintPaint.textSize = size * 0.041f
                canvas.drawText("2.5 SEC TO ${actionVerbShort()}", centerX, centerY + size * 0.095f, hintPaint)
            }
            snapshot.phase == HoldGestureState.Phase.ACCESSIBILITY_READY -> {
                titlePaint.textSize = size * 0.092f
                canvas.drawText("CONFIRM", centerX, centerY - size * 0.035f, titlePaint)
                hintPaint.textSize = size * 0.039f
                canvas.drawText("ACTIVATE TO ${actionVerbShort()}", centerX, centerY + size * 0.075f, hintPaint)
            }
            else -> {
                val title = if (snapshot.phase == HoldGestureState.Phase.TOUCH_HOLDING) "KEEP HOLDING" else "WAIT"
                fitText(titlePaint, title, size * 0.076f, size * 0.060f, size * 0.68f)
                canvas.drawText(title, centerX, centerY - size * 0.105f, titlePaint)
                val remainingTenths = ceil((1f - snapshot.progress) * 25f).toInt().coerceAtLeast(0)
                val remaining = "${remainingTenths / 10}.${remainingTenths % 10}"
                numeralPaint.textSize = size * 0.148f
                canvas.drawText(remaining, centerX, centerY + size * 0.065f, numeralPaint)
                hintPaint.textSize = size * 0.038f
                val cancellationHint = if (snapshot.phase == HoldGestureState.Phase.TOUCH_HOLDING)
                    "RELEASE TO CANCEL" else "ACTIVATE TO CANCEL"
                canvas.drawText(cancellationHint, centerX, centerY + size * 0.145f, hintPaint)
            }
        }

        if (gesture.completeTouch(SystemClock.elapsedRealtime())) {
            touchActive = false
            confirm()
            return
        }
        if (snapshot.phase == HoldGestureState.Phase.ACCESSIBILITY_READY && !accessibilityReadyAnnounced) {
            accessibilityReadyAnnounced = true
            setLabel(Label.ACCESSIBILITY_CONFIRM)
            listener?.onHapticCue(HapticCue.READY_TO_CONFIRM)
            announceForAccessibility("Ready. Activate again to ${actionVerb()}.")
        }
        if (snapshot.phase == HoldGestureState.Phase.IDLE && accessibilityReadyAnnounced) {
            accessibilityReadyAnnounced = false
            setLabel(Label.READY)
            announceForAccessibility("${actionLabel()} confirmation expired.")
        }
        if (snapshot.phase != HoldGestureState.Phase.IDLE) postInvalidateOnAnimation()
    }

    private fun confirmedHint(): String = when (purpose) {
        Purpose.TRIGGER_TEST -> "SENDING"
        Purpose.RESET_TEST -> "RESETTING"
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
        announceForAccessibility("${actionLabel()} cancelled.")
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
        announceForAccessibility("${actionLabel()} confirmed.")
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

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (!hasWindowFocus) cancelForInterruption()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility != VISIBLE) cancelForInterruption()
    }

    override fun onDetachedFromWindow() {
        cancelForInterruption()
        super.onDetachedFromWindow()
    }

    private fun cancelForInterruption() {
        touchActive = false
        parent?.requestDisallowInterceptTouchEvent(false)
        if (!gesture.cancel()) return
        accessibilityReadyAnnounced = false
        setLabel(Label.READY)
        listener?.onHapticCue(HapticCue.CANCELLED)
        invalidate()
    }

    private fun updateContentDescription() {
        contentDescription = "${actionLabel()}. Hold for two and a half seconds."
    }

    private fun actionLabel(): String = when (purpose) {
        Purpose.TRIGGER_TEST -> "TEST alert"
        Purpose.RESET_TEST -> "TEST reset"
    }

    private fun actionVerb(): String = when (purpose) {
        Purpose.TRIGGER_TEST -> "send the TEST alert"
        Purpose.RESET_TEST -> "reset the TEST"
    }

    private fun readyTitle(): String = when (purpose) {
        Purpose.TRIGGER_TEST -> "HOLD"
        Purpose.RESET_TEST -> "HOLD"
    }

    private fun confirmedTitle(): String = when (purpose) {
        Purpose.TRIGGER_TEST -> "TEST CONFIRMED"
        Purpose.RESET_TEST -> "RESET CONFIRMED"
    }

    private fun actionVerbShort(): String = when (purpose) {
        Purpose.TRIGGER_TEST -> "SEND"
        Purpose.RESET_TEST -> "RESET"
    }

    private fun fitText(paint: Paint, text: String, preferred: Float, minimum: Float, maxWidth: Float) {
        paint.textSize = preferred
        if (paint.measureText(text) <= maxWidth) return
        paint.textSize = (preferred * maxWidth / paint.measureText(text)).coerceAtLeast(minimum)
    }

    companion object {
        private const val MOVE_SLOP_FACTOR = 0.53f
    }
}
