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
        color = Color.rgb(190, 194, 202)
        textAlign = Paint.Align.CENTER
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
        titlePaint.textSize = size * 0.078f
        linePaint.textSize = size * 0.048f
        actionTextPaint.textSize = size * 0.052f

        canvas.drawText(title, centerX, height * 0.24f, titlePaint)
        val startY = height * 0.39f
        val lineGap = size * 0.068f
        lines.forEachIndexed { index, line ->
            canvas.drawText(line, centerX, startY + lineGap * index, linePaint)
        }

        actionBounds.setEmpty()
        actionLabel?.let { label ->
            val buttonHeight = size * 0.16f
            actionBounds.set(
                width * 0.20f,
                height - buttonHeight - size * 0.07f,
                width * 0.80f,
                height - size * 0.07f,
            )
            actionPaint.color = if (actionPressed) Color.rgb(82, 52, 58) else Color.rgb(59, 32, 38)
            canvas.drawRoundRect(actionBounds, buttonHeight / 2f, buttonHeight / 2f, actionPaint)
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
}
