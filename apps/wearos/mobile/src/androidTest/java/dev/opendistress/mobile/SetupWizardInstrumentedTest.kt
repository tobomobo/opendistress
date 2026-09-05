// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Only an emulator without Garmin Connect: never runs against a user's paired watch. */
@RunWith(AndroidJUnit4::class)
class SetupWizardInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun preparationGuidanceNeverMutatesSavedSetup() {
        assumeTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        assumeTrue(runCatching { context.packageManager.getPackageInfo("com.garmin.android.apps.connectmobile", 0) }.isFailure)
        val store = SecureProvisioningStore.get(context)
        val before = store.snapshot()
        val prefs = context.getSharedPreferences("watch-target", 0)
        val previousTarget = prefs.getString("selected", null)
        var activity: Activity? = null
        try {
            store.replace(ProvisioningState())
            WatchTargetStore(context).select(WatchTarget.GARMIN)
            val unchanged = store.snapshot()
            activity = instrumentation.startActivitySync(Intent(context, PreparationActivity::class.java)
                .putExtra("garmin_controls", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            val preparation = activity
            onUi {
                assertTrue(text(preparation, "Learn your Garmin controls").isShown)
                assertEquals(1, all(preparation).filterIsInstance<GarminControlDiagram>().size)
                // Every family must resolve its themed colors and render without a watch connection.
                GarminControlLayout.entries.forEach { layout ->
                    val diagram = GarminControlDiagram(preparation, layout)
                    diagram.layout(0, 0, 400, 400)
                    val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
                    diagram.draw(Canvas(bitmap)); bitmap.recycle()
                }
                capture(preparation, "garmin-controls.png")
                click(preparation, "Next · rehearse without looking")
                assertTrue(text(preparation, "Practice without sending").isShown)
                assertEquals(unchanged, store.snapshot())
            }
            instrumentation.waitForIdleSync()
            onUi {
                capture(preparation, "garmin-blind-practice.png")
                click(preparation, "Next · access during sport")
                assertTrue(text(preparation, "Reach the app from everyday use").isShown)
                click(preparation, "Failure checklist · no sending")
                assertTrue(text(preparation, "When something fails").isShown)
                assertEquals(unchanged, store.snapshot())
            }
            instrumentation.waitForIdleSync()
            onUi {
                capture(preparation, "garmin-failure-checks.png")
                click(preparation, "Back to preparation")
                assertTrue(text(preparation, "Practice before you need it").isShown)
                assertTrue(text(preparation, "Save your delivery settings first.").isShown)
                assertEquals(unchanged, store.snapshot())
            }
        } finally {
            activity?.let { onUi { it.finish() } }
            instrumentation.waitForIdleSync()
            store.replace(before)
            prefs.edit().apply { if (previousTarget == null) remove("selected") else putString("selected", previousTarget) }.commit()
        }
    }

    @Test fun wizardKeepsDraftPrivateAndRequiresReviewBeforeSync() {
        assumeTrue(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        val connectInstalled = runCatching { context.packageManager.getPackageInfo("com.garmin.android.apps.connectmobile", 0) }.isSuccess
        assumeTrue(!connectInstalled)
        val store = SecureProvisioningStore.get(context)
        val before = store.snapshot()
        val prefs = context.getSharedPreferences("watch-target", 0)
        val previousTarget = prefs.getString("selected", null)
        var activity: Activity? = null
        try {
            store.replace(ProvisioningState())
            WatchTargetStore(context).select(WatchTarget.GARMIN)
            activity = launch()
            val first = activity
            onUi {
                assertTrue(text(first, "1 of 6 · Delivery").isShown)
                field(first, "Pushover user/group key").setText("A".repeat(30))
                field(first, "Pushover application API token").setText("B".repeat(30))
                click(first, "Continue")
                assertTrue(text(first, "2 of 6 · Response plan").isShown)
                click(first, "Call first · check two words")
                assertEquals(ResponsePlanTemplates.CALLBACK, field(first, "Responder instructions").text.toString())
                click(first, "Continue")
                assertTrue(text(first, "3 of 6 · Your information").isShown)
                field(first, "Protected person name").setText("Example wearer")
                click(first, "Continue")
                assertTrue(text(first, "4 of 6 · Conversation words").isShown)
                val generated = store.snapshot().draft!!.words
                assertTrue(generated.isNotEmpty())
                assertTrue(text(first, generated).isShown)
                click(first, "Continue")
                assertTrue(text(first, "5 of 6 · Watch behavior").isShown)
                click(first, "Continue")
                assertTrue(text(first, "6 of 6 · Review & sync").isShown)
                assertNull(store.snapshot().config)
                val words = store.snapshot().draft!!.words
                assertTrue(words.isNotEmpty())
                val preview = all(first).filterIsInstance<TextView>().first { it.text.startsWith("TEST alert · content preview") }
                assertTrue(preview.text.contains(words))
                assertTrue(preview.text.contains("Do not contact police because of this TEST"))
                assertTrue(preview.text.contains("Example wearer"))
            }
            // Activity restart must restore the encrypted draft, never promote it to saved settings.
            onUi { first.finish() }
            instrumentation.waitForIdleSync()
            activity = launch()
            val resumed = activity
            onUi {
                assertTrue(text(resumed, "6 of 6 · Review & sync").isShown)
                assertNull(store.snapshot().config)
                assertFalse(all(resumed).filterIsInstance<MaterialCheckBox>().first { it.text.startsWith("I reviewed") }.isChecked)
                all(resumed).filterIsInstance<MaterialCheckBox>().first { it.text.startsWith("I reviewed") }.isChecked = true
                click(resumed, "Save and sync to watch")
                assertEquals("Example wearer", store.snapshot().config!!.protectedPersonName)
                val sent = store.snapshot().config!!
                val privateDraft = store.snapshot().draft!!
                assertTrue(store.recordPublish(sent, "watch_1234567890"))
                store.recordAck(dev.opendistress.shared.DirectConfigAck("watch_1234567890", sent.revision, sent.digestSha256(), 1000))
                assertSame(privateDraft, store.snapshot().draft)
                assertNotNull(store.snapshot().confirmed)
                assertFalse(store.recordPublish(sent.copy(revision = sent.revision + 1), "watch_1234567890"))
                assertSame(privateDraft, store.snapshot().draft)
                assertTrue(GarminCompanionProtocol.configMessage(store.snapshot().config!!).values.any {
                    it.toString().contains(store.snapshot().draft!!.words)
                })
                assertTrue(text(resumed, "Prepared, at your pace.").isShown)
                click(resumed, "View my emergency plan")
                assertTrue(text(resumed, store.snapshot().draft!!.words).isShown)
                click(resumed, "Edit response plan")
                assertTrue(text(resumed, "Response plan").isShown)
                field(resumed, "Responder instructions").setText("Draft only, not synced")
                click(resumed, "Close")
                assertEquals(sent.responseInstructions, store.snapshot().config!!.responseInstructions)
            }
            instrumentation.waitForIdleSync()
            onUi { capture(resumed, "companion-home.png"); resumed.finish() }
            instrumentation.waitForIdleSync()
            activity = launch()
            val reopened = activity
            onUi {
                assertTrue(text(reopened, "Prepared, at your pace.").isShown)
                click(reopened, "My plan")
                assertFalse(all(reopened).filterIsInstance<TextView>().any { it.isShown && it.text.toString() == "Draft only, not synced" })
            }
            instrumentation.waitForIdleSync()
            onUi {
                capture(reopened, "companion-saved-plan.png")
                click(reopened, "Settings")
                click(reopened, "Restart setup wizard")
                assertTrue(text(reopened, "1 of 6 · Delivery").isShown)
                assertEquals("A".repeat(30), field(reopened, "Pushover user/group key").text.toString())
                click(reopened, "Continue")
                assertEquals("Draft only, not synced", field(reopened, "Responder instructions").text.toString())
            }
        } finally {
            activity?.let { onUi { it.finish() } }
            instrumentation.waitForIdleSync()
            store.replace(before)
            prefs.edit().apply { if (previousTarget == null) remove("selected") else putString("selected", previousTarget) }.commit()
        }
    }

    private fun launch(): Activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).also {
        instrumentation.waitForIdleSync()
    }
    private fun onUi(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun all(activity: Activity): List<View> {
        fun descend(view: View): List<View> = listOf(view) + if (view is ViewGroup) (0 until view.childCount).flatMap { descend(view.getChildAt(it)) } else emptyList()
        return descend(activity.window.decorView)
    }
    private fun text(activity: Activity, value: String) = all(activity).filterIsInstance<TextView>().first { it.text.toString() == value }
    private fun click(activity: Activity, value: String) {
        val button = all(activity).filterIsInstance<MaterialButton>().first { it.text.toString() == value }
        assertTrue(button.isShown && button.isEnabled)
        assertTrue(button.performClick())
    }
    private fun field(activity: Activity, hint: String): EditText = all(activity).filterIsInstance<TextInputLayout>().first { it.hint.toString() == hint }.editText!!
    private fun capture(activity: Activity, name: String) {
        val view = activity.window.decorView
        // Render this test's own view with dummy credentials, never capture another app or real profile.
        val image = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(image))
        context.getExternalFilesDir(null)!!.resolve(name).outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
}
