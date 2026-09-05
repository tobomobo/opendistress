// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.cos
import kotlin.math.sin

internal enum class GarminControlLayout(val title: String, val startAngle: Double, val backAngle: Double, val hasMenu: Boolean) {
    FENIX("fēnix / five buttons", 30.0, 330.0, true),
    FORERUNNER("Forerunner / five buttons", 21.0, 330.0, true),
    INSTINCT("Instinct / five buttons", 21.0, 340.0, true),
    VENU("Venu / two buttons", 30.0, 330.0, false),
    VENU_X1("Venu X1 / rectangular", 25.0, 335.0, false),
}

/** Original schematic, not a connected-device detection or a Garmin screenshot. */
internal class GarminControlDiagram(context: Context, private val layout: GarminControlLayout) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    init {
        contentDescription = "${layout.title}. START is upper right, BACK lower right." +
            if (layout.hasMenu) " MENU is middle left, DOWN lower left." else " No middle-left MENU button."
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val ink = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
        val primary = MaterialColors.getColor(this, androidx.appcompat.R.attr.colorPrimary)
        val size = minOf(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f; val r = size * .34f
        paint.color = ink; paint.style = Paint.Style.STROKE; paint.strokeWidth = size * .012f
        if (layout == GarminControlLayout.VENU_X1) canvas.drawRoundRect(cx-r, cy-r, cx+r, cy+r, r*.18f, r*.18f, paint)
        else canvas.drawCircle(cx, cy, r, paint)
        fun key(angle: Double, number: String, highlight: Boolean = false) {
            val radians = Math.toRadians(angle)
            val x = if (layout == GarminControlLayout.VENU_X1) cx + r * 1.10f else cx + cos(radians).toFloat() * r * 1.13f
            val y = if (layout == GarminControlLayout.VENU_X1) cy + (if (number == "1") -.5f else .5f) * r
                else cy - sin(radians).toFloat() * r * 1.13f
            paint.style = Paint.Style.FILL; paint.color = if (highlight) primary else ink
            canvas.drawCircle(x, y, size * .06f, paint)
            paint.color = MaterialColors.getColor(this@GarminControlDiagram, com.google.android.material.R.attr.colorSurface)
            paint.textSize = size * .065f; paint.textAlign = Paint.Align.CENTER
            canvas.drawText(number, x, y - (paint.ascent() + paint.descent()) / 2, paint)
        }
        key(layout.startAngle, "1", true); key(layout.backAngle, "2")
        if (layout.hasMenu) { key(180.0, "3"); key(210.0, "4"); key(150.0, "·") }
        paint.color = ink; paint.textSize = size * .11f; paint.textAlign = Paint.Align.CENTER
        canvas.drawText("12:45", cx, cy - (paint.ascent() + paint.descent()) / 2, paint)
    }
}
