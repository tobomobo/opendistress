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
            isLongClickable = value
            invalidate()
        }

    private var ambient = false
    private var pressedAction = Action.NONE
    private val detailsBounds = RectF()
    private val resetBounds = RectF()
    private val handler = Handler(Looper.getMainLooper())
    private val repaintClock = object : Runnable {
        override fun run() {
            invalidate()
            handler.postDelayed(this, if (ambient) 60_000L else 1_000L)
        }
    }

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(225, 228, 234)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(88, 214, 141)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
    }
    private val secondaryPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(180, 185, 194)
        textAlign = Paint.Align.CENTER
    }
    private val actionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(35, 39, 45)
        style = Paint.Style.FILL
    }

    init {
        isClickable = true
        isFocusable = true
        contentDescription = "Alert accepted by provider. Delivery is not confirmed."
    }

    fun setAmbientMode(isAmbient: Boolean) {
        if (ambient == isAmbient) return
        ambient = isAmbient
        linePaint.isAntiAlias = !ambient
        accentPaint.isAntiAlias = !ambient
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
        val centerX = width / 2f
        val dialCenterY = height * 0.41f
        val dialRadius = size * 0.225f

        textPaint.textSize = size * 0.071f
        canvas.drawText("ALERT ACCEPTED", centerX, height * 0.135f, textPaint)

        linePaint.strokeWidth = size * 0.009f
        canvas.drawCircle(centerX, dialCenterY, dialRadius, linePaint)
        drawTicks(canvas, centerX, dialCenterY, dialRadius, size)
        drawHands(canvas, centerX, dialCenterY, dialRadius, size)

        secondaryPaint.textSize = maxOf(size * 0.042f, sp(10f))
        canvas.drawText("Delivery not confirmed", centerX, height * 0.675f, secondaryPaint)

        if (!ambient) drawActions(canvas, size)
    }

    private fun drawTicks(canvas: Canvas, centerX: Float, centerY: Float, radius: Float, size: Float) {
        linePaint.strokeWidth = size * 0.008f
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
        canvas.drawCircle(centerX, centerY, size * 0.018f, accentPaint)
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
        val heightPx = maxOf(size * 0.15f, 48f * resources.displayMetrics.density)
        val top = height - heightPx - size * 0.035f
        val horizontalMargin = size * 0.12f
        val gap = size * 0.025f
        val availableWidth = width - horizontalMargin * 2 - if (showResetAction) gap else 0f
        val actionWidth = if (showResetAction) availableWidth / 2f else availableWidth
        detailsBounds.set(horizontalMargin, top, horizontalMargin + actionWidth, top + heightPx)
        resetBounds.set(detailsBounds.right + gap, top, width - horizontalMargin, top + heightPx)

        actionPaint.color = if (pressedAction == Action.DETAILS) Color.rgb(56, 61, 70) else Color.rgb(35, 39, 45)
        canvas.drawRoundRect(detailsBounds, heightPx / 2f, heightPx / 2f, actionPaint)
        secondaryPaint.textSize = maxOf(size * 0.047f, sp(10f))
        secondaryPaint.color = Color.WHITE
        canvas.drawText("DETAILS", detailsBounds.centerX(), detailsBounds.centerY() - (secondaryPaint.ascent() + secondaryPaint.descent()) / 2f, secondaryPaint)
        if (showResetAction) {
            actionPaint.color = if (pressedAction == Action.RESET) Color.rgb(76, 43, 47) else Color.rgb(53, 30, 34)
            canvas.drawRoundRect(resetBounds, heightPx / 2f, heightPx / 2f, actionPaint)
            canvas.drawText("RESET", resetBounds.centerX(), resetBounds.centerY() - (secondaryPaint.ascent() + secondaryPaint.descent()) / 2f, secondaryPaint)
        }
        secondaryPaint.color = Color.rgb(180, 185, 194)
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

    /** Reset is a separately announced long-click action and remains disabled by default. */
    override fun performLongClick(): Boolean {
        if (!showResetAction) return false
        super.performLongClick()
        listener?.onResetRequested()
        return true
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
