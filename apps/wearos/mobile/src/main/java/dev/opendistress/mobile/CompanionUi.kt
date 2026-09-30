// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView

/**
 * Small shared kit for the programmatic companion screens: one type scale, one
 * card, one callout and three button weights. Presentation only.
 */
internal class CompanionUi(private val context: Context) {
    enum class Tone { NOTE, WARNING, OK }

    /** Spacing scale in dp. Every gap on these screens is one of these steps. */
    object Space { const val XS = 4; const val S = 8; const val M = 12; const val L = 16; const val XL = 24; const val XXL = 32 }

    fun dp(value: Int): Int = (value * context.resources.displayMetrics.density + 0.5f).toInt()
    fun attr(attribute: Int, fallback: Int = Color.GRAY): Int = MaterialColors.getColor(context, attribute, fallback)

    val onSurface get() = attr(com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
    val muted get() = attr(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY)
    val cardColor get() = attr(com.google.android.material.R.attr.colorSurfaceContainerLow, Color.WHITE)
    val hairline get() = attr(com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY)
    val accent get() = context.getColor(R.color.opendistress_amber_deep)
    val amber get() = context.getColor(R.color.opendistress_amber)

    fun eyebrow(value: String) = text(value, R.style.TextAppearance_OpenDistress_Eyebrow, accent).apply {
        isAllCaps = true
        setPadding(0, 0, 0, dp(Space.XS))
    }

    fun heading(value: String) = text(value, R.style.TextAppearance_OpenDistress_Hero, onSurface).apply {
        setPadding(0, 0, 0, dp(Space.M))
    }

    fun title(value: String) = text(value, R.style.TextAppearance_OpenDistress_Section, onSurface)

    fun body(value: String, color: Int = muted) =
        text(value, R.style.TextAppearance_OpenDistress_Body, color).apply { setPadding(0, 0, 0, dp(Space.M)) }

    fun caption(value: String) =
        text(value, com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, muted).apply {
            setLineSpacing(0f, 1.15f)
        }

    fun text(value: String, appearance: Int, color: Int) = MaterialTextView(context).apply {
        text = value
        setTextAppearance(appearance)
        setTextColor(color)
    }

    /** Rounded surface with a hairline border; children stack vertically. */
    fun card(vararg children: View, padding: Int = 20): MaterialCardView = MaterialCardView(context).apply {
        radius = dp(20).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = hairline
        setCardBackgroundColor(cardColor)
        addView(column(*children).apply { setPadding(dp(padding), dp(padding), dp(padding), dp(padding)) })
    }

    fun column(vararg children: View) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        children.forEach { addView(it, fill()) }
    }

    /** Title and body kept apart; an optional badge (step number) leads the row. */
    fun infoCard(title: String, body: String, badge: String? = null): MaterialCardView {
        val copy = column(this.title(title), caption(body).apply { setPadding(0, dp(Space.XS + 2), 0, 0) })
        if (badge == null) return card(copy)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // Centre the badge on the first title line.
            addView(badge(badge), LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(Space.M + 2); topMargin = dp(1) })
            addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return card(row)
    }

    fun badge(value: String) = MaterialTextView(context).apply {
        text = value
        gravity = Gravity.CENTER
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        setTextColor(context.getColor(R.color.opendistress_graphite))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(amber) }
    }

    /** Tinted note with an accent bar; WARNING is for consequences, never decoration. */
    fun callout(value: String, tone: Tone = Tone.NOTE): View {
        val (fill, ink) = when (tone) {
            Tone.NOTE -> attr(com.google.android.material.R.attr.colorSecondaryContainer) to
                attr(com.google.android.material.R.attr.colorOnSecondaryContainer)
            Tone.WARNING -> attr(com.google.android.material.R.attr.colorErrorContainer) to
                attr(com.google.android.material.R.attr.colorOnErrorContainer)
            Tone.OK -> context.getColor(R.color.opendistress_ok_container) to context.getColor(R.color.opendistress_on_ok_container)
        }
        return caption(value).apply {
            setTextColor(ink)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(fill) }
        }
    }

    fun primaryButton(label: String, onClick: () -> Unit = {}) = MaterialButton(context).apply {
        text = label
        styleButton(this, 56)
        // The text appearance resets colour; restore the on-primary ink.
        setTextColor(attr(com.google.android.material.R.attr.colorOnPrimary, Color.WHITE))
        setOnClickListener { onClick() }
    }

    /** Quiet filled button for secondary actions. */
    fun tonalButton(label: String, onClick: () -> Unit = {}) =
        MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = label
            tonal(this)
            setOnClickListener { onClick() }
        }

    fun textButton(label: String, onClick: () -> Unit = {}) =
        MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = label
            styleButton(this, 48)
            setTextColor(onSurface)
            setOnClickListener { onClick() }
        }

    /** A list row: left-aligned label with a chevron, for navigation. */
    fun rowButton(label: String, onClick: () -> Unit = {}) = tonalButton(label, onClick).apply {
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
        setIconResource(R.drawable.ic_chevron_right)
        iconGravity = MaterialButton.ICON_GRAVITY_END
        iconTint = ColorStateList.valueOf(muted)
        setPadding(dp(20), 0, dp(Space.M), 0)
        minHeight = dp(56)
        backgroundTintList = ColorStateList.valueOf(cardColor)
        strokeWidth = dp(1)
        strokeColor = ColorStateList.valueOf(hairline)
    }

    fun tonal(button: MaterialButton) {
        styleButton(button, 52)
        button.strokeWidth = 0
        button.backgroundTintList = ColorStateList.valueOf(
            attr(com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.LTGRAY))
        button.setTextColor(onSurface)
    }

    private fun styleButton(button: MaterialButton, height: Int) {
        button.minHeight = dp(height)
        button.cornerRadius = dp(16)
        button.insetTop = 0
        button.insetBottom = 0
        button.isAllCaps = false
        button.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
        button.textSize = 15f
    }

    /**
     * Vertical rhythm for stacked content: a run of buttons starts 16 dp below
     * text and keeps 8 dp between buttons; stacked cards and callouts keep 12 dp.
     * Only fills gaps that were left at zero, so explicit margins still win.
     */
    fun rhythm(container: ViewGroup) {
        var previous: View? = null
        for (i in 0 until container.childCount) {
            val child = container.getChildAt(i)
            if (child is ViewGroup && child !is MaterialButton && child !is TextInputLayout) rhythm(child)
            val params = child.layoutParams as? LinearLayout.LayoutParams
            val vertical = (container as? LinearLayout)?.orientation == LinearLayout.VERTICAL
            if (params != null && vertical && previous != null && params.topMargin == 0) {
                params.topMargin = when {
                    child is MaterialButton -> dp(if (previous is MaterialButton) Space.S else Space.L)
                    child is MaterialCardView -> dp(Space.M)
                    previous is MaterialButton || previous is MaterialCardView -> dp(Space.L)
                    else -> 0
                }
                child.layoutParams = params
            }
            if (child.visibility != View.GONE) previous = child
        }
    }

    fun fill(topMargin: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { this.topMargin = topMargin }
}
