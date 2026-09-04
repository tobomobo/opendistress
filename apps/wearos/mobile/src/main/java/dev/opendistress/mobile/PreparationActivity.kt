// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.textview.MaterialTextView
import dev.opendistress.shared.DirectConfig
import java.text.DateFormat
import java.util.Date

/** A guided physical drill, deliberately incapable of sending a notification. */
class PreparationActivity : Activity() {
    private lateinit var content: LinearLayout
    private lateinit var store: SecureProvisioningStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface))
            addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        })
        try {
            store = SecureProvisioningStore.get(this)
            overview()
        } catch (_: Exception) {
            text("Saved preparation could not be opened securely. Return to setup.")
        }
    }

    private fun overview() {
        newPage()
        heading("Practice before you need it")
        text("Preview your saved profile, then rehearse with each recipient. This screen never sends an alert. Start the TEST deliberately on your watch.")
        val state = store.snapshot()
        val config = state.config
        if (config == null) {
            text("Save your delivery settings first.")
            button("Back to setup") { finish() }
            return
        }
        card("Your response plan", config.responseInstructions.ifBlank {
            "No plan saved. Agree who should act, how to verify your safety, and when to contact emergency services."
        })
        button("Preview saved emergency profile") { preview(config) }
        val providers = buildList {
            if (config.grafanaWebhookUrl != null) add("Grafana")
            if (config.pushoverUserKey != null) add("Pushover")
        }
        text("Watch setup confirmation is separate from a successful drill. Records below are your observations, not automatic delivery or acknowledgement evidence. Repeat after changing setup or the receiving phone.")
        val selected = WatchTargetStore(this).selected()
        val watches = when (selected) {
            WatchTarget.GARMIN -> listOf("Garmin")
            WatchTarget.PIXEL -> listOf("Wear OS")
            null -> emptyList()
        }
        for (watch in watches) {
            for (provider in providers) {
                val evidence = state.drills.find { it.watch == watch && it.provider == provider }
                val now = System.currentTimeMillis() / 1000
                val status = when {
                    evidence == null -> "No physical drill recorded"
                    evidence.revision != config.revision -> "Setup changed — repeat the drill"
                    !evidence.isCurrent(config.revision, now) -> "Drill older than 30 days or clock changed — repeat"
                    else -> "Owner recorded a successful drill"
                }
                val date = evidence?.let {
                    "\nRecorded: ${DateFormat.getDateTimeInstance().format(Date(it.recordedAt * 1000))}"
                }.orEmpty()
                card("$watch → $provider", status + date)
                button("Rehearse $watch → $provider") { drill(config, watch, provider) }
            }
        }
        button("Back to setup") { finish() }
    }

    private fun preview(config: DirectConfig) {
        newPage()
        heading("Saved emergency profile")
        text("TEST ONLY — no emergency action required. This is a profile preview; provider formatting and notification truncation differ. Verify the actual message during a drill.")
        card("Response instructions", config.responseInstructions)
        card("Prepared message", config.customAlertMessage)
        card("Person wearing the watch", config.protectedPersonName)
        card("Description of that person", config.personDescription)
        card("Children / family", config.childrenInfo)
        card("Home address — NOT current location", config.homeAddress)
        card("Background / medical / language / access information", config.backgroundInfo)
        card("Photo link (not downloaded)", config.profilePhotoUrl)
        text("Police are not contacted automatically. GPS follows in separate updates; check source, accuracy and age. Your selected providers can read the profile and location. Garmin setup also passes through Garmin Connect.")
        button("Back to preparation") { overview() }
    }

    private fun drill(config: DirectConfig, watch: String, provider: String) {
        newPage()
        heading("$watch → $provider drill")
        text("Arrange this with ALL intended recipients first. TEST alerts can be loud and repeat. Use only a test route. Do not call emergency services for this exercise.")
        if (config.grafanaWebhookUrl != null && config.pushoverUserKey != null) {
            text("With both routes saved, Grafana is tried first; Pushover is a fallback. A retry can reach both providers, so warn both recipient groups. Only record the provider you actually observed. To isolate a route, save only that provider and sync again.")
        }
        val checks = listOf(
            "I confirmed this saved setup on the intended watch and informed every recipient.",
            "I opened OpenDistress on the watch, held the trigger for 2.5 seconds, and saw provider acceptance (double vibration / analog cover).",
            "Every intended recipient saw the TEST in $provider on their locked phone, with the intended sound and Do Not Disturb behavior.",
            "Recipients checked the protected person and response plan, then acknowledged the TEST inside $provider.",
            "Recipients received a real GPS update, checked its age and accuracy, and verified the map against my actual position.",
            "I reset the TEST explicitly on the watch and checked that provider alarm repetitions have stopped.",
        )
        text("If any step fails, leave it unchecked and fix that part before repeating. An unavailable or stale GPS fix is useful information, but does not pass this fresh-location drill.")
        val boxes = checks.map { label ->
            MaterialCheckBox(this).apply {
                text = label
                setPadding(0, dp(8), 0, dp(8))
                content.addView(this, params())
            }
        }
        val record = button("Record my successful physical drill") {
            try {
                store.recordDrill(DrillEvidence(config.revision, watch, provider, System.currentTimeMillis() / 1000))
                overview()
            } catch (_: Exception) {
                text("Could not record this drill. Setup may have changed. Return to preparation and repeat with the current setup.")
            }
        }.apply { isEnabled = false }
        boxes.forEach { box ->
            box.setOnCheckedChangeListener { _, _ -> record.isEnabled = boxes.all { it.isChecked } }
        }
        button("Leave without recording success") { overview() }
    }

    private fun newPage() {
        content.removeAllViews()
        content.post { (content.parent as? ScrollView)?.scrollTo(0, 0) }
    }

    private fun heading(value: String) = text(value, true)

    private fun text(value: String, heading: Boolean = false) {
        content.addView(MaterialTextView(this).apply {
            text = value
            setTextAppearance(if (heading) com.google.android.material.R.style.TextAppearance_Material3_HeadlineMedium
                else com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
            setPadding(0, dp(8), 0, dp(12))
        }, params())
    }

    private fun card(title: String, body: String) {
        content.addView(MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = 0f
            addView(MaterialTextView(this@PreparationActivity).apply {
                text = "$title\n\n${body.ifBlank { "Not provided" }}"
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                setPadding(dp(18), dp(18), dp(18), dp(18))
            })
        }, params())
    }

    private fun button(label: String, action: () -> Unit): MaterialButton = MaterialButton(this).apply {
        text = label
        minHeight = dp(56)
        setOnClickListener { action() }
        content.addView(this, params())
    }

    private fun params() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
}
