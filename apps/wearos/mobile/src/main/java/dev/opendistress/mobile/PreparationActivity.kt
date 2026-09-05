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
            if (intent.getBooleanExtra("garmin_controls", false)) controls() else overview()
        } catch (_: Exception) {
            text("Saved preparation could not be opened securely. Return to setup.")
        }
    }

    private fun overview() {
        newPage()
        heading("Practice before you need it")
        text("Preview your saved profile, then rehearse with each recipient. This screen never sends an alert. Start the TEST deliberately on your watch.")
        if (WatchTargetStore(this).selected() == WatchTarget.GARMIN) {
            button("Learn Garmin controls · no sending") { controls() }
            button("Failure checklist · no sending") { failureChecklist() }
        }
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
            text("Garmin attempts both saved providers independently. Wear OS tries Grafana first, with Pushover as fallback. Retries can reach both, so warn both recipient groups. Only record the provider you actually observed. To isolate a route, save only that provider and sync again.")
        }
        val checks = listOf(
            "I confirmed this saved setup on the intended watch and informed every recipient.",
            "I opened OpenDistress on the watch, held the trigger for 2.5 seconds, and saw provider acceptance (double vibration / clock cover).",
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

    private fun controls(layout: GarminControlLayout = GarminControlLayout.FENIX) {
        newPage()
        heading("Learn your Garmin controls")
        text("Choose the matching layout and check it against your own watch. This is a schematic, not automatic device detection. Nothing on this phone screen sends an alert or changes watch settings.")
        content.addView(GarminControlDiagram(this, layout), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(230)))
        button("Layout: ${layout.title}") {
            android.app.AlertDialog.Builder(this).setTitle("Watch control layout")
                .setItems(GarminControlLayout.entries.map { it.title }.toTypedArray()) { _, index ->
                    controls(GarminControlLayout.entries[index])
                }.show()
        }
        card("1 · START / ENTER", "Upper right. In the real app, hold 2.5 seconds to send. A short press does not send. First learn this in Practice, which never sends anything.")
        card("2 · BACK", "Lower right. In Practice, exit back to the real app. In reset options, cancel without clearing the event. Ordinary taps on the accepted clock do not reveal alert details.")
        card(if (layout.hasMenu) "3 · MENU / UP" else "Touch controls",
            if (layout.hasMenu) "Middle left. Hold from an idle app to enter Practice. After provider acceptance, hold to reveal status; hold again for reset options. Reset then requires a separate 2.5-second START hold."
            else "Tap the idle app to enter Practice. After provider acceptance, hold the touchscreen to reveal status. Tap Reset options there; a separate 2.5-second hardware START hold is required to reset.")
        if (layout.hasMenu) card("4 · DOWN", "Lower left. Does not trigger an alert or reveal the covered status. In status, returns to the clock.")
        button("Next · rehearse without looking") { blindPractice(layout) }
        button("Back to preparation") { overview() }
    }

    private fun blindPractice(layout: GarminControlLayout) {
        newPage(); heading("Practice without sending")
        card("1 · Enter Practice on the watch", "Open OpenDistress while no TEST or incident is pending. ${if (layout.hasMenu) "Hold middle-left MENU." else "Tap the idle screen."} Verify the PRACTICE screen and its no-sending message before proceeding. Practice never starts automatically and cannot send notifications.")
        card("2 · Learn the hold", "Follow the watch: release a short press early, then hold START for 2.5 seconds. Repeat the hold looking away, in a safe setting. BACK leaves Practice; the ordinary app can send again after you exit.")
        card("3 · Learn the cues", "One short pulse means input recognized. Two short pulses simulate provider acceptance in Practice. In the real app, they mean the provider accepted the request—not that a phone received it or someone is helping. If vibration is disabled, no pulse is expected. Change it in Watch behavior, then save and sync.")
        text("Check that vibration is perceptible but acceptably quiet on your actual wrist. The simulator cannot establish either. Do not restrain yourself for this exercise.")
        button("Next · access during sport") { accessPractice() }
        button("Back to controls") { controls(layout) }
    }

    private fun accessPractice() {
        newPage(); heading("Reach the app from everyday use")
        card("From the normal watch face", "Find the installed OpenDistress app. Put its app-list entry or glance somewhere easy to reach. If your firmware offers it as a shortcut target, assign and test that shortcut. The app cannot install a global button listener.")
        card("On fēnix 8", "From the ordinary watch face, hold middle-left MENU, then Watch Settings → System → Shortcuts. Only choose options the watch actually offers. If OpenDistress is absent, a watch-face shortcut plus a pinned app/glance may shorten the route; it is not a one-button trigger.")
        card("During a safe practice activity", "With no alert active, rehearse getting to OpenDistress without stopping or discarding the recording. Enter Practice, repeat the hold, then return and verify the activity is still recording. Do not assume the simulator or another watch model proves this route works.")
        text("Once an alert is active, leaving OpenDistress can stop its foreground GPS acquisition. A system shortcut, screen lock, firmware behavior, or app exit can still interrupt it. Agree on a backup route with your recipients.")
        button("Next · separate delivery test") { overview() }
        button("Failure checklist · no sending") { failureChecklist() }
    }

    private fun failureChecklist() {
        newPage(); heading("When something fails")
        text("Use only an isolated TEST route with all recipients warned. These checks do not run automatically and do not record success. Never disable connectivity or GPS during a real incident.")
        card("No phone / no network", "A locally retained event is not delivery. Observe pending state, restore the connection, and check the actual receiver. Retries can duplicate notifications. Do not reset pending work to make an error disappear.")
        card("No GPS / old GPS", "The initial TEST must not wait for GPS. Check that no fresh location is claimed without a real fix; if a last-known fix exists, verify its age warning. Move to open sky and check a later real update with the recipient.")
        card("App closed / reopened", "A pending event must retain its identity. An accepted event must reopen covered, without sending a new trigger. Garmin foreground GPS needs the app open. Reopening or a compile success is not proof that updates reached anyone.")
        card("Reset", "Open status deliberately, then reset options, then hold START 2.5 seconds. Cancel once to verify the event survives. Reset stops local TEST tracking; check and stop any remaining provider alarm repetitions separately.")
        button("Back to preparation") { overview() }
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
