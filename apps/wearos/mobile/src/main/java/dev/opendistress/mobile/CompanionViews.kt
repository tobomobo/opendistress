// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.google.android.material.color.MaterialColors

/** Setup readiness as the brand ring. It shows saved/sync state, never delivery. */
internal enum class RingState { EMPTY, WAITING, READY, ATTENTION }

internal class ReadinessRingView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    var state: RingState = RingState.EMPTY
        set(value) { field = value; invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = minOf(width, height).toFloat()
        val stroke = size * .09f
        val r = size / 2f - stroke
        val cx = width / 2f; val cy = height / 2f
        val box = RectF(cx - r, cy - r, cx + r, cy + r)
        val track = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
        val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        val amber = context.getColor(R.color.opendistress_amber)
        val error = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorError)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = stroke
        paint.color = track
        canvas.drawArc(box, 0f, 360f, false, paint)
        // Android angles run clockwise from three o'clock: 90 is six o'clock.
        val sweep = when (state) { RingState.READY -> 150f; RingState.WAITING -> 80f; else -> 0f }
        if (sweep > 0f) {
            paint.color = if (state == RingState.READY) ink else amber
            canvas.drawArc(box, 90f + 30f, sweep, false, paint)
            canvas.drawArc(box, 90f - 30f - sweep, sweep, false, paint)
        }
        paint.style = Paint.Style.FILL
        paint.color = if (state == RingState.ATTENTION) error else amber
        canvas.drawCircle(cx, cy + r, stroke * .95f, paint)
    }
}

/** Segmented wizard progress: one pill per step, the current one in amber. */
internal class StepProgressView(context: Context, private val steps: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var current: Int = 0
        set(value) { field = value; invalidate(); contentDescription = "Step ${value + 1} of $steps" }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gap = height * 1.5f
        val w = (width - gap * (steps - 1)) / steps
        val done = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        val todo = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)
        val amber = context.getColor(R.color.opendistress_amber)
        for (i in 0 until steps) {
            paint.color = when { i < current -> done; i == current -> amber; else -> todo }
            val left = i * (w + gap)
            canvas.drawRoundRect(left, 0f, left + w, height.toFloat(), height / 2f, height / 2f, paint)
        }
    }
}
