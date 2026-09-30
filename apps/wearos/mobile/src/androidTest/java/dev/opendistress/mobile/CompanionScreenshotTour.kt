// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Renders every companion screen with synthetic values into app-private files
 * for design review. Emulator only; nothing is synced and nothing is sent.
 * Views are drawn directly, so FLAG_SECURE stays enabled.
 */
@RunWith(AndroidJUnit4::class)
class CompanionScreenshotTour {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun captureEveryScreen() {
        assumeTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val store = SecureProvisioningStore.get(context)
        val before = store.snapshot()
        val prefs = context.getSharedPreferences("watch-target", 0)
        val previousTarget = prefs.getString("selected", null)
        var activity: Activity? = null
        try {
            store.replace(ProvisioningState())
            WatchTargetStore(context).select(WatchTarget.GARMIN)
            activity = launch(MainActivity::class.java)
            val main = activity
            shot(main, "01-delivery")
            step { field(main, "Pushover user/group key").setText("A".repeat(30)) }
            step { field(main, "Pushover application API token").setText("B".repeat(30)) }
            step { click(main, "Continue") }; shot(main, "02-plan")
            step { click(main, "Call first · check two words") }; shot(main, "03-plan-filled")
            step { click(main, "Continue") }
            step { field(main, "Protected person name").setText("Alex Example") }; shot(main, "04-profile")
            step { click(main, "Continue") }; shot(main, "05-words")
            step { click(main, "Continue") }; shot(main, "06-watch")
            step { click(main, "Continue") }; shot(main, "07-review")
            step {
                all(main).filterIsInstance<MaterialCheckBox>().first { it.text.startsWith("I reviewed") }.isChecked = true
                click(main, "Save and sync to watch")
            }
            shot(main, "08-home")
            step { click(main, "View my emergency plan") }; shot(main, "09-my-plan")
            step { click(main, "Settings") }; shot(main, "10-settings")
            step { main.finish() }
            activity = launch(PreparationActivity::class.java)
            val prep = activity
            shot(prep, "11-preparation")
            step { click(prep, "Learn Garmin controls · no sending") }; shot(prep, "12-controls")
            step { click(prep, "Next · rehearse without looking") }; shot(prep, "13-practice")
            step { click(prep, "Next · access during sport") }; shot(prep, "14-access")
            step { click(prep, "Failure checklist · no sending") }; shot(prep, "15-failures")
        } finally {
            activity?.let { onUi { it.finish() } }
            instrumentation.waitForIdleSync()
            store.replace(before)
            prefs.edit().apply { if (previousTarget == null) remove("selected") else putString("selected", previousTarget) }.commit()
        }
    }

    private fun launch(type: Class<out Activity>): Activity =
        instrumentation.startActivitySync(Intent(context, type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).also {
            instrumentation.waitForIdleSync()
        }
    private fun onUi(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun step(block: () -> Unit) { onUi(block); instrumentation.waitForIdleSync() }
    /** Waits off the UI thread so label and ripple animations finish before drawing. */
    private fun shot(activity: Activity, name: String) {
        Thread.sleep(450)
        instrumentation.waitForIdleSync()
        onUi { capture(activity, name) }
    }
    private fun all(activity: Activity): List<View> {
        fun descend(view: View): List<View> = listOf(view) +
            if (view is ViewGroup) (0 until view.childCount).flatMap { descend(view.getChildAt(it)) } else emptyList()
        return descend(activity.window.decorView)
    }
    private fun field(activity: Activity, hint: String) =
        all(activity).filterIsInstance<TextInputLayout>().first { it.hint.toString() == hint }.editText!!
    private fun click(activity: Activity, value: String) {
        all(activity).filterIsInstance<MaterialButton>().first { it.text.toString() == value && it.isShown }.performClick()
    }

    /** Draws the window plus the full height of the visible scroll content. */
    private fun capture(activity: Activity, name: String) {
        val decor = activity.window.decorView
        decor.measure(View.MeasureSpec.makeMeasureSpec(decor.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(decor.height, View.MeasureSpec.EXACTLY))
        decor.layout(0, 0, decor.width, decor.height)
        val window = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        decor.draw(Canvas(window))
        save(window, "$name-screen.png")
        val scroll = all(activity).filterIsInstance<ScrollView>().firstOrNull { it.isShown && it.childCount > 0 }
        if (scroll != null) {
            val child = scroll.getChildAt(0)
            val full = Bitmap.createBitmap(child.width, child.height.coerceAtMost(12000), Bitmap.Config.ARGB_8888)
            val canvas = Canvas(full)
            canvas.drawColor(com.google.android.material.color.MaterialColors.getColor(
                scroll, com.google.android.material.R.attr.colorSurface))
            child.draw(canvas)
            save(full, "$name-full.png")
        }
    }

    private fun save(bitmap: Bitmap, name: String) {
        val dir = context.getExternalFilesDir("tour")!!.apply { mkdirs() }
        dir.resolve(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
