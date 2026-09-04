// SPDX-License-Identifier: MIT
package dev.opendistress.wear.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import dev.opendistress.wear.R
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Low-power analog confirmation shown only after verified provider acceptance.
 *
 * "Accepted" deliberately does not claim recipient delivery, acknowledgment, or
 * incident resolution. The owner decides whether a reset action is safe to expose.
 */
class ProviderAcceptedAnalogView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    interface Listener {
        fun onDetailsRequested()
        fun onResetRequested()
    }

    var listener: Listener? = null
    var showResetAction: Boolean = false
        set(value) {
            field = value
            sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
            invalidate()
        }

    private var ambient = false
    private var burnInProtectionRequired = false
    private var pressedAction = Action.NONE
    private val detailsBounds = RectF()
    private val resetBounds = RectF()
    private val modeBounds = RectF()
    private val handler = Handler(Looper.getMainLooper())
    private val repaintClock = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, if (ambient) 60_000L else 1_000L)
        }
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(232, 224, 225)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(109, 213, 140)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val dialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(29, 27, 30)
        style = Paint.Style.FILL
    }
    private val modePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(43, 40, 45)
        style = Paint.Style.FILL
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 248, 247)
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(211, 196, 198)
        textAlign = Paint.Align.CENTER
    }
    private val actionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(35, 39, 45)
        style = Paint.Style.FILL
    }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "TEST alert accepted by provider. Delivery is not confirmed."
    }

    fun setAmbientMode(
        isAmbient: Boolean,
        lowBitAmbient: Boolean = false,
        burnInProtectionRequired: Boolean = false,
    ) {
        if (
            ambient == isAmbient &&
            this.burnInProtectionRequired == burnInProtectionRequired
        ) return
        ambient = isAmbient
        this.burnInProtectionRequired = isAmbient && burnInProtectionRequired
        val antiAlias = !isAmbient || !lowBitAmbient
        listOf(linePaint, accentPaint, dialPaint, modePaint, textPaint, secondaryPaint, actionPaint).forEach {
            it.isAntiAlias = antiAlias
        }
        handler.removeCallbacks(repaintClock)
        if (isAttachedToWindow) handler.post(repaintClock)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        handler.post(repaintClock)
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(repaintClock)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val size = min(width, height).toFloat()
        val contentSave = canvas.save()
        if (ambient && burnInProtectionRequired) {
            val offset = size * 0.012f
            when (Calendar.getInstance().get(Calendar.MINUTE) % 4) {
                0 -> canvas.translate(-offset, 0f)
                1 -> canvas.translate(0f, -offset)
                2 -> canvas.translate(offset, 0f)
                else -> canvas.translate(0f, offset)
            }
        }
        val centerX = width / 2f
        val dialCenterY = height * 0.405f
        val dialRadius = size * 0.218f

        val chipWidth = size * 0.25f
        val chipHeight = size * 0.072f
        modeBounds.set(
            centerX - chipWidth / 2f,
            height * 0.068f,
            centerX + chipWidth / 2f,
            height * 0.068f + chipHeight,
        )
        if (!ambient) canvas.drawRoundRect(modeBounds, chipHeight / 2f, chipHeight / 2f, modePaint)
        secondaryPaint.color = Color.rgb(211, 196, 198)
        secondaryPaint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        secondaryPaint.letterSpacing = 0.11f
        secondaryPaint.textSize = size * 0.030f
        canvas.drawText(
            "TEST ACTIVE",
            centerX,
            modeBounds.centerY() - (secondaryPaint.ascent() + secondaryPaint.descent()) / 2f,
            secondaryPaint,
        )
        secondaryPaint.letterSpacing = 0f

        textPaint.textSize = size * 0.060f
        canvas.drawText("PROVIDER ACCEPTED", centerX, height * 0.190f, textPaint)

        if (!ambient) canvas.drawCircle(centerX, dialCenterY, dialRadius, dialPaint)
        linePaint.color = Color.rgb(73, 67, 74)
        linePaint.strokeWidth = size * 0.008f
        canvas.drawCircle(centerX, dialCenterY, dialRadius, linePaint)
        drawTicks(canvas, centerX, dialCenterY, dialRadius, size)
        drawHands(canvas, centerX, dialCenterY, dialRadius, size)

        secondaryPaint.color = Color.rgb(109, 213, 140)
        secondaryPaint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        secondaryPaint.textSize = maxOf(size * 0.037f, sp(9f))
        canvas.drawText("ALERT ACTIVE", centerX, height * 0.640f, secondaryPaint)
        secondaryPaint.color = Color.rgb(211, 196, 198)
        secondaryPaint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        secondaryPaint.textSize = maxOf(size * 0.037f, sp(9f))
        canvas.drawText("Delivery unconfirmed", centerX, height * 0.690f, secondaryPaint)

        if (!ambient) drawActions(canvas, size)
        canvas.restoreToCount(contentSave)
    }

    private fun drawTicks(canvas: Canvas, centerX: Float, centerY: Float, radius: Float, size: Float) {
        linePaint.color = Color.rgb(232, 224, 225)
        linePaint.strokeWidth = size * 0.007f
        repeat(12) { index ->
            val angle = Math.toRadians(index * 30.0 - 90.0)
            val outerX = centerX + cos(angle).toFloat() * radius * 0.90f
            val outerY = centerY + sin(angle).toFloat() * radius * 0.90f
            val innerX = centerX + cos(angle).toFloat() * radius * 0.80f
            val innerY = centerY + sin(angle).toFloat() * radius * 0.80f
            canvas.drawLine(innerX, innerY, outerX, outerY, linePaint)
        }
    }

    private fun drawHands(canvas: Canvas, centerX: Float, centerY: Float, radius: Float, size: Float) {
        val now = Calendar.getInstance()
        val seconds = now.get(Calendar.SECOND)
        val minutes = now.get(Calendar.MINUTE) + seconds / 60f
        val hours = now.get(Calendar.HOUR) + minutes / 60f
        drawHand(canvas, centerX, centerY, radius * 0.52f, hours * 30f - 90f, size * 0.026f, linePaint)
        drawHand(canvas, centerX, centerY, radius * 0.75f, minutes * 6f - 90f, size * 0.016f, linePaint)
        if (!ambient) {
            drawHand(canvas, centerX, centerY, radius * 0.80f, seconds * 6f - 90f, size * 0.006f, accentPaint)
        }
        accentPaint.style = Paint.Style.FILL
        canvas.drawCircle(centerX, centerY, size * 0.018f, accentPaint)
        accentPaint.style = Paint.Style.STROKE
    }

    private fun drawHand(
        canvas: Canvas,
        centerX: Float,
        centerY: Float,
        length: Float,
        degrees: Float,
        width: Float,
        paint: Paint,
    ) {
        val angle = Math.toRadians(degrees.toDouble())
        paint.strokeWidth = width
        canvas.drawLine(
            centerX,
            centerY,
            centerX + cos(angle).toFloat() * length,
            centerY + sin(angle).toFloat() * length,
            paint,
        )
    }

    private fun drawActions(canvas: Canvas, size: Float) {
        val heightPx = maxOf(size * 0.145f, 48f * resources.displayMetrics.density)
        val top = height - heightPx - size * 0.025f
        val horizontalMargin = size * 0.105f
        val gap = size * 0.025f
        val availableWidth = width - horizontalMargin * 2 - if (showResetAction) gap else 0f
        val actionWidth = if (showResetAction) availableWidth / 2f else availableWidth
        detailsBounds.set(horizontalMargin, top, horizontalMargin + actionWidth, top + heightPx)
        resetBounds.set(detailsBounds.right + gap, top, width - horizontalMargin, top + heightPx)

        actionPaint.color = if (pressedAction == Action.DETAILS) Color.rgb(72, 67, 74) else Color.rgb(43, 40, 45)
        drawPressedCapsule(canvas, detailsBounds, pressedAction == Action.DETAILS, actionPaint, size)
        secondaryPaint.textSize = maxOf(size * 0.041f, sp(10f))
        secondaryPaint.color = Color.rgb(255, 248, 247)
        secondaryPaint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        canvas.drawText("DETAILS", detailsBounds.centerX(), detailsBounds.centerY() - (secondaryPaint.ascent() + secondaryPaint.descent()) / 2f, secondaryPaint)
        if (showResetAction) {
            actionPaint.color = if (pressedAction == Action.RESET) Color.rgb(91, 56, 60) else Color.rgb(63, 35, 39)
            drawPressedCapsule(canvas, resetBounds, pressedAction == Action.RESET, actionPaint, size)
            canvas.drawText("RESET", resetBounds.centerX(), resetBounds.centerY() - (secondaryPaint.ascent() + secondaryPaint.descent()) / 2f, secondaryPaint)
        }
        secondaryPaint.color = Color.rgb(211, 196, 198)
        secondaryPaint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
    }

    private fun drawPressedCapsule(
        canvas: Canvas,
        bounds: RectF,
        pressed: Boolean,
        paint: Paint,
        size: Float,
    ) {
        if (!pressed) {
            canvas.drawRoundRect(bounds, bounds.height() / 2f, bounds.height() / 2f, paint)
            return
        }
        val inset = size * 0.008f
        val pressedBounds = RectF(bounds).apply { inset(inset, inset) }
        canvas.drawRoundRect(
            pressedBounds,
            pressedBounds.height() / 2f,
            pressedBounds.height() / 2f,
            paint,
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (ambient || !isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedAction = actionAt(event.x, event.y)
                if (pressedAction == Action.NONE) return false
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (actionAt(event.x, event.y) != pressedAction) {
                    pressedAction = Action.NONE
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val action = pressedAction
                val confirmed = action != Action.NONE && actionAt(event.x, event.y) == action
                pressedAction = Action.NONE
                invalidate()
                if (confirmed) {
                    super.performClick()
                    when (action) {
                        Action.DETAILS -> listener?.onDetailsRequested()
                        Action.RESET -> listener?.onResetRequested()
                        Action.NONE -> Unit
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                pressedAction = Action.NONE
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Accessibility click opens details; it never changes incident state. */
    override fun performClick(): Boolean {
        super.performClick()
        listener?.onDetailsRequested()
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
        info.addAction(
            AccessibilityNodeInfo.AccessibilityAction(
                AccessibilityNodeInfo.ACTION_CLICK,
                "Open TEST details",
            ),
        )
        if (showResetAction) {
            info.addAction(
                AccessibilityNodeInfo.AccessibilityAction(
                    R.id.accessibility_action_reset_test,
                    "Start TEST reset confirmation",
                ),
            )
        }
    }

    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean = when (action) {
        R.id.accessibility_action_reset_test -> {
            if (!showResetAction) false else {
                // The owner must show a deliberate confirmation surface; this view never clears state.
                listener?.onResetRequested()
                true
            }
        }
        else -> super.performAccessibilityAction(action, arguments)
    }

    private fun actionAt(x: Float, y: Float): Action = when {
        detailsBounds.contains(x, y) -> Action.DETAILS
        showResetAction && resetBounds.contains(x, y) -> Action.RESET
        else -> Action.NONE
    }

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private enum class Action { NONE, DETAILS, RESET }
}
