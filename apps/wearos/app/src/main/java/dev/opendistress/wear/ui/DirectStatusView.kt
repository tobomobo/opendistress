// SPDX-License-Identifier: MIT
package dev.opendistress.wear.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/** A round-safe status surface used separately from the analog confirmation dial. */
class DirectStatusView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    var title: String = ""
        set(value) {
            field = value
            updateDescription()
            invalidate()
        }
    var lines: List<String> = emptyList()
        set(value) {
            field = value.take(4)
            updateDescription()
            invalidate()
        }
    var actionLabel: String? = null
        set(value) {
            field = value
            isClickable = value != null
            updateDescription()
            invalidate()
        }
    var onAction: (() -> Unit)? = null

    private val actionBounds = RectF()
    private val contentBounds = RectF()
    private var actionPressed = false
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.BOLD,
        )
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(211, 196, 198)
        textAlign = Paint.Align.CENTER
    }
    private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(29, 27, 30)
        style = Paint.Style.FILL
    }
    private val statePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val stateTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
    }
    private val actionPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val actionTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.BOLD,
        )
    }

    init {
        isFocusable = true
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        val size = min(width, height).toFloat()
        val centerX = width / 2f
        val hasAction = actionLabel != null
        val tone = toneFor(title)
        statePaint.color = tone.container
        stateTextPaint.color = tone.content
        val stateRadius = size * 0.070f
        canvas.drawCircle(centerX, height * 0.135f, stateRadius, statePaint)
        stateTextPaint.textSize = size * 0.065f
        canvas.drawText(
            tone.symbol,
            centerX,
            height * 0.135f - (stateTextPaint.ascent() + stateTextPaint.descent()) / 2f,
            stateTextPaint,
        )

        fitText(titlePaint, title, size * 0.070f, size * 0.048f, size * 0.75f)
        linePaint.textSize = size * 0.041f
        actionTextPaint.textSize = size * 0.042f

        canvas.drawText(title, centerX, height * 0.275f, titlePaint)
        val lineCount = lines.size.coerceAtLeast(1)
        val startY = height * 0.405f
        val lineGap = size * 0.061f
        val panelTop = height * 0.335f
        val panelBottom = startY + lineGap * (lineCount - 1) + size * 0.052f
        contentBounds.set(width * 0.115f, panelTop, width * 0.885f, panelBottom)
        canvas.drawRoundRect(contentBounds, size * 0.052f, size * 0.052f, surfacePaint)
        lines.forEachIndexed { index, line ->
            fitText(linePaint, line, size * 0.041f, size * 0.032f, contentBounds.width() * 0.88f)
            canvas.drawText(line, centerX, startY + lineGap * index, linePaint)
        }

        actionBounds.setEmpty()
        actionLabel?.let { label ->
            val buttonHeight = maxOf(size * 0.15f, 48f * resources.displayMetrics.density)
            actionBounds.set(
                width * 0.13f,
                height - buttonHeight - size * 0.035f,
                width * 0.87f,
                height - size * 0.035f,
            )
            actionPaint.color = if (actionPressed) tone.pressedAction else tone.action
            val drawBounds = if (actionPressed) {
                RectF(actionBounds).apply { inset(size * 0.008f, size * 0.008f) }
            } else {
                actionBounds
            }
            canvas.drawRoundRect(drawBounds, drawBounds.height() / 2f, drawBounds.height() / 2f, actionPaint)
            fitText(actionTextPaint, label, size * 0.042f, size * 0.034f, actionBounds.width() * 0.82f)
            canvas.drawText(
                label,
                actionBounds.centerX(),
                actionBounds.centerY() - (actionTextPaint.ascent() + actionTextPaint.descent()) / 2f,
                actionTextPaint,
            )
        }

        if (!hasAction) actionPressed = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (actionLabel == null || !isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                actionPressed = actionBounds.contains(event.x, event.y)
                if (!actionPressed) return false
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val next = actionBounds.contains(event.x, event.y)
                if (next != actionPressed) {
                    actionPressed = next
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val activate = actionPressed && actionBounds.contains(event.x, event.y)
                actionPressed = false
                invalidate()
                if (activate) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                actionPressed = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        onAction?.invoke()
        return onAction != null
    }

    private fun updateDescription() {
        contentDescription = buildList {
            if (title.isNotBlank()) add(title)
            addAll(lines.filter(String::isNotBlank))
            actionLabel?.let { add("Action: $it") }
        }.joinToString(". ")
    }

    private fun fitText(paint: Paint, text: String, preferred: Float, minimum: Float, maxWidth: Float) {
        paint.textSize = preferred
        if (paint.measureText(text) <= maxWidth) return
        paint.textSize = (preferred * maxWidth / paint.measureText(text)).coerceAtLeast(minimum)
    }

    private fun toneFor(value: String): Tone = when {
        value.contains("ERROR") || value.contains("FAILED") || value.contains("UNREADABLE") -> Tone(
            symbol = "!",
            container = Color.rgb(147, 0, 10),
            content = Color.rgb(255, 218, 214),
            action = Color.rgb(99, 30, 36),
            pressedAction = Color.rgb(123, 42, 49),
        )
        value.contains("REQUIRED") || value.contains("CHANGED") || value.contains("UNAVAILABLE") -> Tone(
            symbol = "i",
            container = Color.rgb(83, 67, 0),
            content = Color.rgb(255, 226, 124),
            action = Color.rgb(76, 60, 8),
            pressedAction = Color.rgb(98, 79, 17),
        )
        else -> Tone(
            symbol = "·",
            container = Color.rgb(23, 78, 47),
            content = Color.rgb(177, 241, 198),
            action = Color.rgb(38, 65, 51),
            pressedAction = Color.rgb(51, 83, 66),
        )
    }

    private data class Tone(
        val symbol: String,
        val container: Int,
        val content: Int,
        val action: Int,
        val pressedAction: Int,
    )
}
