// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import dev.opendistress.shared.DirectConfig

class MainActivity : Activity(), DataClient.OnDataChangedListener {
    private lateinit var coordinator: ProvisioningCoordinator
    private lateinit var garminLink: GarminCompanionLink
    private lateinit var status: MaterialTextView
    private lateinit var statusTitle: MaterialTextView
    private lateinit var statusIndicator: MaterialTextView
    private lateinit var statusCard: MaterialCardView
    private lateinit var garminStatus: MaterialTextView
    private lateinit var garminStatusTitle: MaterialTextView
    private lateinit var garminStatusIndicator: MaterialTextView
    private lateinit var garminStatusCard: MaterialCardView
    private lateinit var locationAssist: MaterialSwitch
    private lateinit var save: MaterialButton
    private lateinit var haptics: MaterialSwitch
    private lateinit var draftStatus: MaterialTextView
    private val targetStore by lazy { WatchTargetStore(this) }
    private val isGarmin get() = targetStore.selected() == WatchTarget.GARMIN
    private val isPixel get() = targetStore.selected() == WatchTarget.PIXEL
    private val fields = linkedMapOf<String, EditText>()
    private val fieldLayouts = linkedMapOf<String, TextInputLayout>()
    private val garminObserver: (GarminLinkStatus) -> Unit = ::showGarminStatus
    private var changingLocationSwitch = false
    private val wizardPages = mutableListOf<LinearLayout>()
    private var wizardStep = 0
    private lateinit var wizardScroll: ScrollView
    private lateinit var wizardTitle: MaterialTextView
    private lateinit var wizardProgress: LinearProgressIndicator
    private lateinit var previousStep: MaterialButton
    private lateinit var nextStep: MaterialButton
    private lateinit var review: MaterialTextView
    private lateinit var consent: MaterialCheckBox
    private lateinit var wordsView: MaterialTextView
    private lateinit var wordsAgreement: MaterialCheckBox
    private var conversationWords = ""
    private var storageReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        buildInterface()
        garminLink = GarminCompanionLink.get(this)
        coordinator = try {
            ProvisioningCoordinator(this)
        } catch (_: Exception) {
            showWearStatus(getString(R.string.storage_authentication_failed))
            save.isEnabled = false
            return
        }
        coordinator.snapshot().config?.let(::populate)
        coordinator.snapshot().draft?.let { draft ->
            draft.values.forEach { (key, value) -> fields[key]?.setText(value) }
            haptics.isChecked = draft.haptics
            conversationWords = draft.words
            wordsAgreement.isChecked = draft.wordsLearned
            wizardStep = draft.step
            draftStatus.text = "Phone draft restored · review and save to apply edits. Watch status refers to the last saved setup, not this preview."
            draftStatus.visibility = View.VISIBLE
        }
        storageReady = true
        showWizardStep(wizardStep)
        showWearStatus(coordinator.statusDescription())
        locationAssist.isChecked = garminLink.locationAssistEnabled() && hasFineLocation()
        save.setOnClickListener { saveConfiguration() }
        findViewById<MaterialButton>(SYNC_BUTTON_ID).setOnClickListener {
            if (isPixel) coordinator.synchronize(::showWearStatus, force = true)
            if (isGarmin) coordinator.snapshot().config?.let(garminLink::sync) ?: garminLink.refresh()
        }
        locationAssist.setOnCheckedChangeListener { _, enabled -> changeLocationAssist(enabled) }
        fields.values.forEach { field ->
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    draftStatus.visibility = View.VISIBLE
                    consent.isChecked = false
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        haptics.setOnCheckedChangeListener { _, _ ->
            draftStatus.visibility = View.VISIBLE
            consent.isChecked = false
        }
        if (targetStore.selected() == null) chooseWatch()
    }

    override fun onStart() {
        super.onStart()
        if (::coordinator.isInitialized) {
            if (isPixel) {
                Wearable.getDataClient(this).addListener(this)
                coordinator.synchronize(::showWearStatus)
            }
            if (isGarmin) {
                garminLink.observe(garminObserver)
                garminLink.resume()
            }
        }
    }

    override fun onStop() {
        if (::coordinator.isInitialized) Wearable.getDataClient(this).removeListener(this)
        if (::garminLink.isInitialized) garminLink.removeObserver(garminObserver)
        super.onStop()
    }

    override fun onPause() {
        if (storageReady) persistDraft(showError = false)
        wordsView.text = if (conversationWords.isEmpty()) "No words generated" else "Saved words · tap Reveal"
        super.onPause()
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        coordinator.handleEvents(dataEvents, ::showWearStatus)
    }

    private fun saveConfiguration() {
        if (!consent.isChecked) {
            showSetupError("Review the message and confirm your response plan before syncing.")
            return
        }
        if (!persistDraft()) return
        if (conversationWords.isNotEmpty() && !wordsAgreement.isChecked) {
            showWizardStep(3)
            showSetupError("Learn your two words, then confirm that you can recall them. The expected words will be sent with your briefing.")
            return
        }
        if (value("responseInstructions").isEmpty()) {
            showWizardStep(1)
            showSetupError("Please agree and enter a response plan first. A delivery destination alone does not tell recipients what to do.")
            return
        }
        if (targetStore.selected() == null) { chooseWatch(); return }
        val previous = coordinator.snapshot().config?.revision
        val config = try {
            DirectConfig(
                revision = nextRevision(previous, System.currentTimeMillis()),
                grafanaWebhookUrl = value("grafanaWebhookUrl").nullIfBlank(),
                pushoverUserKey = value("pushoverUserKey").nullIfBlank(),
                pushoverApiToken = value("pushoverApiToken").nullIfBlank(),
                protectedPersonName = value("protectedPersonName"),
                customAlertMessage = value("customAlertMessage"),
                homeAddress = value("homeAddress"),
                childrenInfo = value("childrenInfo"),
                personDescription = value("personDescription"),
                backgroundInfo = value("backgroundInfo"),
                responseInstructions = ResponsePlanTemplates.compile(value("responseInstructions"), conversationWords),
                profilePhotoUrl = value("profilePhotoUrl"),
                hapticFeedback = haptics.isChecked,
            )
        } catch (error: IllegalArgumentException) {
            showSetupError(error.message ?: "Configuration is invalid")
            return
        }
        try {
            coordinator.save(config, ::showWearStatus)
            draftStatus.visibility = View.GONE
            if (isGarmin) garminLink.sync(config)
        } catch (_: Exception) {
            showSetupError("Configuration could not be stored securely — nothing was sent")
        }
    }

    private fun showSetupError(message: String) {
        if (isGarmin) showGarminStatus(GarminLinkStatus.Attention(message)) else showWearStatus(message)
        MaterialAlertDialogBuilder(this).setTitle("Setup not sent").setMessage(message)
            .setPositiveButton("OK", null).show()
    }

    private fun chooseWatch() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Which watch are you setting up?")
            .setItems(arrayOf("Garmin · via Garmin Connect", "Pixel Watch / Wear OS")) { _, index ->
                targetStore.select(if (index == 0) WatchTarget.GARMIN else WatchTarget.PIXEL)
                recreate()
            }
            .setCancelable(targetStore.selected() != null)
            .show()
    }

    private fun buildInterface() {
        val surface = color(com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val onSurface = color(com.google.android.material.R.attr.colorOnSurface, Color.rgb(36, 25, 26))
        val onSurfaceVariant = color(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY)
        val primary = color(androidx.appcompat.R.attr.colorPrimary, Color.rgb(140, 29, 39))
        val outline = color(com.google.android.material.R.attr.colorOutline, Color.rgb(133, 115, 116))
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
        }
        page.addView(MaterialToolbar(this).apply {
            title = getString(R.string.app_bar_title)
            subtitle = getString(R.string.app_bar_subtitle)
            setTitleTextAppearance(this@MainActivity, R.style.TextAppearance_OpenDistress_Brand)
            setTitleTextColor(onSurface)
            setSubtitleTextColor(onSurfaceVariant)
            setBackgroundColor(surface)
            contentInsetStartWithNavigation = dp(20)
            setContentInsetsRelative(dp(20), dp(20))
        }, matchWidth())

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(32))
        }
        repeat(6) { wizardPages.add(LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }) }
        val routePage = wizardPages[0]
        val planPage = wizardPages[1]
        val profilePage = wizardPages[2]
        val wordsPage = wizardPages[3]
        val watchPage = wizardPages[4]
        val reviewPage = wizardPages[5]
        routePage.addView(wizardCopy("Prepare once. Rehearse together.", true))
        routePage.addView(wizardCopy("Choose where your TEST alerts go. Recipients and escalation are configured in Grafana or Pushover, not by entering names here. No test is sent during setup."))
        routePage.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = if (isGarmin) "Garmin · Change watch" else if (isPixel) "Pixel Watch · Change watch" else "Choose your watch"
            minHeight = dp(48)
            setOnClickListener {
                MaterialAlertDialogBuilder(this@MainActivity).setTitle("Change setup destination?")
                    .setMessage("Your profile and private draft stay on this phone. The selected watch can receive your last saved setup; this does not erase the previous watch.")
                    .setNegativeButton("Keep editing", null)
                    .setPositiveButton("Choose watch") { _, _ -> if (persistDraft()) chooseWatch() }.show()
            }
        }, matchWidth())
        reviewPage.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Preparation & physical drill"
            minHeight = dp(56)
            setOnClickListener { startActivity(Intent(this@MainActivity, PreparationActivity::class.java)) }
        }, matchWidth(topMargin = dp(12)))

        val delivery = addSection(
            routePage,
            R.string.delivery_title,
            R.string.delivery_explanation,
        )
        addField(delivery, "grafanaWebhookUrl", R.string.grafana_webhook, 512, secret = true)
        addField(delivery, "pushoverUserKey", R.string.pushover_user_key, 30, secret = true)
        addField(delivery, "pushoverApiToken", R.string.pushover_api_token, 30, secret = true)

        val emergency = addSection(
            profilePage,
            R.string.emergency_card,
            R.string.emergency_card_explanation,
        )
        addField(emergency, "protectedPersonName", R.string.protected_person_name, 40)
        addField(emergency, "customAlertMessage", R.string.prepared_alert_message, 240, multiline = true)
        addField(emergency, "homeAddress", R.string.home_address, 120, multiline = true)
        addField(emergency, "childrenInfo", R.string.children_information, 150, multiline = true)
        addField(emergency, "personDescription", R.string.person_description, 150, multiline = true)
        addField(emergency, "backgroundInfo", R.string.background_information, 180, multiline = true)
        planPage.addView(wizardCopy("What should your people do?", true))
        planPage.addView(wizardCopy("Write what recipients should do immediately, including whether calling is safe and what to do if you cannot respond. Your briefing travels with every initial alert; recipients do not need to remember it. This does not call anyone automatically. Choose a starting point and adapt it."))
        listOf("Quiet response · do not call" to ResponsePlanTemplates.QUIET,
            "Call first · check two words" to ResponsePlanTemplates.CALLBACK).forEach { (label, template) ->
            planPage.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = label
                minHeight = dp(52)
                setOnClickListener {
                    val field = fields.getValue("responseInstructions")
                    if (field.text.isNullOrBlank()) field.setText(template)
                    else MaterialAlertDialogBuilder(this@MainActivity).setTitle("Replace your response plan?")
                        .setMessage("This replaces the current text with a starting template. Review and adapt it before saving.")
                        .setNegativeButton("Keep mine", null)
                        .setPositiveButton("Use template") { _, _ -> field.setText(template) }.show()
                }
            }, matchWidth())
        }
        addField(planPage, "responseInstructions", R.string.response_instructions, 180, multiline = true)
        buildConversationWords(wordsPage)
        addField(emergency, "profilePhotoUrl", R.string.profile_photo_url, 512)

        statusIndicator = MaterialTextView(this).apply {
            gravity = Gravity.CENTER
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
            setTextColor(onSurface)
        }
        statusTitle = MaterialTextView(this).apply {
            setTextAppearance(R.style.TextAppearance_OpenDistress_Status)
            setTextColor(onSurface)
        }
        status = MaterialTextView(this).apply {
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(onSurfaceVariant)
            setLineSpacing(0f, 1.08f)
            accessibilityLiveRegion = MaterialTextView.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val statusCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(statusTitle, matchWidth())
            addView(status, matchWidth(topMargin = dp(4)))
        }
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(statusIndicator, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                marginEnd = dp(14)
            })
            addView(statusCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        statusCard = MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            addView(statusRow, matchWidth())
        }
        val watchSection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        watchSection.addView(MaterialTextView(this).apply {
            setText(R.string.watch_readiness)
            setTextAppearance(R.style.TextAppearance_OpenDistress_Section)
            setTextColor(onSurface)
            setPadding(dp(4), dp(28), dp(4), dp(10))
        }, matchWidth())
        watchSection.addView(MaterialTextView(this).apply {
            setText(R.string.pixel_watch_label)
            visibility = if (isPixel) View.VISIBLE else View.GONE
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setTextColor(onSurfaceVariant)
            setPadding(dp(4), 0, dp(4), dp(8))
        }, matchWidth())
        statusCard.visibility = if (isPixel) View.VISIBLE else View.GONE
        watchSection.addView(statusCard, matchWidth())

        garminStatusIndicator = statusIndicator(onSurface)
        garminStatusTitle = statusTitle(onSurface)
        garminStatus = statusBody(onSurfaceVariant)
        garminStatusCard = statusCard(garminStatusIndicator, garminStatusTitle, garminStatus)
        watchSection.addView(MaterialTextView(this).apply {
            setText(R.string.garmin_watch_label)
            visibility = if (isGarmin) View.VISIBLE else View.GONE
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setTextColor(onSurfaceVariant)
            setPadding(dp(4), dp(18), dp(4), dp(8))
        }, matchWidth())
        garminStatusCard.visibility = if (isGarmin) View.VISIBLE else View.GONE
        watchSection.addView(garminStatusCard, matchWidth())
        draftStatus = MaterialTextView(this).apply {
            text = "Unsaved changes · save and sync to apply. Status below refers to your last saved setup."
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(onSurfaceVariant)
            setPadding(dp(4), dp(12), dp(4), dp(8))
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        watchSection.addView(draftStatus, 1, matchWidth())
        reviewPage.addView(watchSection, matchWidth(topMargin = dp(8)))

        val locationCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(MaterialTextView(this@MainActivity).apply {
                setText(R.string.phone_location_assist)
                setTextAppearance(R.style.TextAppearance_OpenDistress_Section)
                setTextColor(onSurface)
            }, matchWidth())
            addView(MaterialTextView(this@MainActivity).apply {
                setText(R.string.phone_location_explanation)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(onSurfaceVariant)
                setLineSpacing(0f, 1.1f)
            }, matchWidth(topMargin = dp(5)))
        }
        locationAssist = MaterialSwitch(this).apply {
            contentDescription = getString(R.string.phone_location_assist)
        }
        val locationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(18), dp(12), dp(18))
            addView(locationCopy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(locationAssist, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginStart = dp(12) })
        }
        watchPage.addView(wizardCopy("How your watch helps", true))
        watchPage.addView(MaterialCardView(this).apply {
            visibility = if (isGarmin) View.VISIBLE else View.GONE
            radius = dp(24).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(color(
                com.google.android.material.R.attr.colorSurfaceContainerLow,
                Color.WHITE,
            ))
            addView(locationRow, matchWidth())
        }, matchWidth(topMargin = dp(18)))
        haptics = MaterialSwitch(this).apply {
            text = "Watch vibration feedback"
            isChecked = true
            minHeight = dp(56)
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        watchPage.addView(haptics, matchWidth(topMargin = dp(16)))
        watchPage.addView(MaterialTextView(this).apply {
            text = "Brief cues on the watch. Two short pulses mean provider acceptance, not delivery or help on the way. Sound and strength depend on the watch. Save and sync to apply."
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
            setTextColor(onSurfaceVariant)
            setPadding(dp(18), 0, dp(18), dp(12))
        }, matchWidth())
        save = MaterialButton(this).apply {
            setText(R.string.save_and_send)
            minHeight = dp(56)
            cornerRadius = dp(28)
            textSize = 15f
            insetTop = 0
            insetBottom = 0
        }
        review = wizardCopy("")
        reviewPage.addView(review, 0, matchWidth())
        consent = MaterialCheckBox(this).apply {
            text = "I reviewed this briefing, including any expected words, and approve sending it to my watch and alert recipients."
            minHeight = dp(56)
        }
        reviewPage.addView(consent, matchWidth(topMargin = dp(12)))
        reviewPage.addView(save, matchWidth(topMargin = dp(16)))
        reviewPage.addView(MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            id = SYNC_BUTTON_ID
            setText(R.string.retry_sync)
            minHeight = dp(52)
            cornerRadius = dp(26)
            textSize = 15f
            insetTop = 0
            insetBottom = 0
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(outline)
            backgroundTintList = ColorStateList.valueOf(surface)
            setTextColor(primary)
        }, matchWidth(topMargin = dp(10)))
        reviewPage.addView(MaterialTextView(this).apply {
            setText(R.string.readiness_explanation)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(onSurfaceVariant)
            setLineSpacing(0f, 1.12f)
            setPadding(dp(4), dp(16), dp(4), 0)
        })

        wizardTitle = wizardCopy("", true).apply { setPadding(dp(20), dp(12), dp(20), dp(16)) }
        page.addView(wizardTitle, matchWidth())
        wizardProgress = LinearProgressIndicator(this).apply { max = 6 }
        page.addView(wizardProgress, matchWidth())
        wizardPages.forEach { content.addView(it, matchWidth()) }
        wizardScroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }
        page.addView(wizardScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        previousStep = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Back"
            minHeight = dp(52)
            setOnClickListener { if (persistDraft()) showWizardStep(wizardStep - 1) }
        }
        nextStep = MaterialButton(this).apply {
            text = "Continue"
            minHeight = dp(52)
            setOnClickListener {
                if (wizardStep == 0) {
                    try {
                        DirectConfig(1, grafanaWebhookUrl = value("grafanaWebhookUrl").nullIfBlank(),
                            pushoverUserKey = value("pushoverUserKey").nullIfBlank(),
                            pushoverApiToken = value("pushoverApiToken").nullIfBlank(),
                            protectedPersonName = "", customAlertMessage = "", homeAddress = "",
                            childrenInfo = "", personDescription = "", backgroundInfo = "",
                            responseInstructions = "", profilePhotoUrl = "").validate()
                    } catch (error: IllegalArgumentException) {
                        showSetupError(error.message ?: "Please check your delivery destination")
                        return@setOnClickListener
                    }
                }
                if (persistDraft()) showWizardStep(wizardStep + 1)
            }
        }
        page.addView(LinearLayout(this).apply {
            setPadding(dp(20), dp(8), dp(20), dp(8))
            addView(previousStep, LinearLayout.LayoutParams(0, dp(56), 1f))
            addView(nextStep, LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(12) })
        }, matchWidth())
        showWizardStep(0)
        setContentView(page.apply {
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val keyboard = insets.getInsets(WindowInsets.Type.ime())
                view.setPadding(0, bars.top, 0, maxOf(bars.bottom, keyboard.bottom))
                insets
            }
        })
    }

    private fun wizardCopy(copy: String, heading: Boolean = false) = MaterialTextView(this).apply {
        text = copy
        setTextAppearance(if (heading) com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall
            else com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
        setTextColor(color(com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY))
        setLineSpacing(0f, 1.12f)
        setPadding(dp(4), dp(12), dp(4), dp(16))
    }

    private fun addSection(parent: LinearLayout, label: Int, explanation: Int): LinearLayout {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(wizardCopy(getString(label), true), matchWidth())
            addView(wizardCopy(getString(explanation)), matchWidth())
        }
        parent.addView(body, matchWidth())
        return body
    }

    private fun showWizardStep(step: Int) {
        wizardStep = step.coerceIn(0, 5)
        val titles = listOf("Delivery", "Response plan", "Your information", "Conversation words", "Watch behavior", "Review & sync")
        wizardTitle.text = "${wizardStep + 1} of 6 · ${titles[wizardStep]}"
        wizardProgress.progress = wizardStep + 1
        wizardPages.forEachIndexed { index, view -> view.visibility = if (index == wizardStep) View.VISIBLE else View.GONE }
        previousStep.isEnabled = wizardStep > 0
        nextStep.visibility = if (wizardStep < 5) View.VISIBLE else View.GONE
        if (wizardStep == 3) wordsView.text = if (conversationWords.isEmpty()) "No words generated" else "Saved words · tap Reveal"
        fieldLayouts["responseInstructions"]?.apply {
            counterMaxLength = ResponsePlanTemplates.planBudget(conversationWords)
            helperText = "$counterMaxLength characters available for instructions; the remaining space is reserved for the expected words. Nothing is silently shortened."
        }
        if (wizardStep == 5) {
            review.text = buildString {
                append("TEST alert · content preview\n\nEXERCISE ONLY. Do not contact police because of this TEST. Rehearse the briefing below.\n\n")
                val providers = listOfNotNull(
                    "Grafana".takeIf { value("grafanaWebhookUrl").isNotEmpty() },
                    "Pushover".takeIf { value("pushoverUserKey").isNotEmpty() || value("pushoverApiToken").isNotEmpty() },
                )
                append("Shared with: ${providers.joinToString().ifEmpty { "No provider configured" }}\n")
                append("Provider layouts and length limits may shorten this content. GPS is added when available, with freshness information. Home address is never a current location.\n\n")
                val briefing = runCatching { ResponsePlanTemplates.compile(value("responseInstructions"), conversationWords) }
                append("Response briefing (exercise only)\n")
                append(briefing.getOrElse { "Not ready: ${it.message}" })
                append("\n\n")
                listOf("customAlertMessage" to "Prepared message",
                    "protectedPersonName" to "Person sending the alert", "personDescription" to "Description of this person",
                    "homeAddress" to "Home address (not live location)", "childrenInfo" to "Dependants / care",
                    "backgroundInfo" to "Relevant background", "profilePhotoUrl" to "Photo link").forEach { (key, label) ->
                    if (value(key).isNotEmpty()) append("$label\n${value(key)}\n\n")
                }
                if (value("responseInstructions").isEmpty()) append("No response plan entered. Agree one with your recipients before syncing.\n\n")
                append("Conversation words: included in the reviewed briefing and shared with the watch and selected providers. ")
                append(if (conversationWords.isEmpty()) "Not set."
                    else if (wordsAgreement.isChecked) "Marked as learned." else "Learn them before saving.")
                append("\n\nSaving sends settings, not an alert. Synced does not mean a recipient has received or acknowledged a test.")
            }
        }
        wizardScroll.post { wizardScroll.scrollTo(0, 0) }
    }

    private fun persistDraft(showError: Boolean = true): Boolean {
        if (!storageReady) return false
        return try {
            SecureProvisioningStore.get(this).saveDraft(SetupDraft(
                fields.mapValues { it.value.text.toString() }, wizardStep, haptics.isChecked,
                conversationWords, wordsAgreement.isChecked,
            ))
            true
        } catch (_: Exception) {
            draftStatus.text = "Draft could not be saved securely. Keep this screen open and retry."
            draftStatus.visibility = View.VISIBLE
            if (showError) MaterialAlertDialogBuilder(this).setTitle("Draft not saved")
                .setMessage("Your edits could not be stored securely. Nothing from this draft was sent to the watch.")
                .setPositiveButton("OK", null).show()
            false
        }
    }

    private fun buildConversationWords(parent: LinearLayout) {
        parent.addView(wizardCopy("Learn once. Include automatically.", true), matchWidth())
        parent.addView(wizardCopy("For a callback check, learn these two words now. Your recipients receive the expected words and your instructions with the alert; they do not need to memorize anything beforehand.\n\nAfter review and sync, the words are stored on the watch and sent via Grafana or Pushover, which can read them. Garmin setup passes through Garmin Connect. These are not wallet words. Correct words alone do not prove safety or end an incident."), matchWidth())
        wordsView = wizardCopy("No words generated", true).apply { isSaveEnabled = false }
        parent.addView(wordsView, matchWidth())
        wordsAgreement = MaterialCheckBox(this).apply {
            text = "I can recall both words without opening the app."
            isSaveEnabled = false
            setOnCheckedChangeListener { _, checked ->
                if (checked && conversationWords.isEmpty()) isChecked = false
                consent.isChecked = false
            }
        }
        parent.addView(MaterialButton(this).apply {
            text = "Generate two new words"
            minHeight = dp(52)
            setOnClickListener {
                fun generate() {
                    try {
                        val words = assets.open("bip39-english.txt").bufferedReader().use { it.readLines() }
                        conversationWords = ConversationWords.generate(words)
                        wordsAgreement.isChecked = false
                        consent.isChecked = false
                        if (persistDraft()) wordsView.text = conversationWords
                    } catch (_: Exception) {
                        showSetupError("Conversation words could not be generated and stored securely.")
                    }
                }
                if (conversationWords.isEmpty()) generate()
                else MaterialAlertDialogBuilder(this@MainActivity).setTitle("Replace your conversation words?")
                    .setMessage("Learn the new pair, then review and sync. Until sync is confirmed, the watch still has the previously saved briefing.")
                    .setNegativeButton("Keep words", null).setPositiveButton("Replace") { _, _ -> generate() }.show()
            }
        }, matchWidth())
        parent.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Reveal / hide words"
            minHeight = dp(52)
            setOnClickListener {
                wordsView.text = if (conversationWords.isEmpty()) "No words generated"
                    else if (wordsView.text.toString() == conversationWords) "Saved words · tap Reveal" else conversationWords
            }
        }, matchWidth())
        parent.addView(wordsAgreement, matchWidth())
        parent.addView(MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Remove this agreement"
            minHeight = dp(48)
            setOnClickListener {
                if (conversationWords.isNotEmpty()) MaterialAlertDialogBuilder(this@MainActivity).setTitle("Remove conversation words?")
                    .setMessage("Review your response plan and sync after removal. Until then the watch may still send the old briefing and words.")
                    .setNegativeButton("Keep", null).setPositiveButton("Remove") { _, _ ->
                        conversationWords = ""
                        wordsAgreement.isChecked = false
                        consent.isChecked = false
                        persistDraft()
                        wordsView.text = "No words generated"
                    }.show()
            }
        }, matchWidth())
        parent.addView(wizardCopy("Vocabulary: the public BIP39 English word list. This is not a wallet mnemonic, password or cryptographic authentication."), matchWidth())
    }

    private fun addField(
        parent: LinearLayout,
        key: String,
        label: Int,
        maxLength: Int,
        secret: Boolean = false,
        multiline: Boolean = false,
    ) {
        val field = TextInputEditText(this).apply {
            isSaveEnabled = false // sensitive drafts live only in the encrypted store, never saved-instance state
            background = null
            setPadding(dp(16), dp(24), dp(16), dp(12))
            filters = arrayOf(InputFilter.LengthFilter(maxLength))
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            if (multiline) {
                minLines = 3
                maxLines = 6
                gravity = Gravity.TOP or Gravity.START
            }
            importantForAutofill = if (secret) EditText.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS else
                EditText.IMPORTANT_FOR_AUTOFILL_AUTO
        }
        fields[key] = field
        parent.addView(TextInputLayout(this).apply {
            fieldLayouts[key] = this
            hint = getString(label)
            isCounterEnabled = !secret
            counterMaxLength = maxLength
            helperText = when (key) {
                "homeAddress" -> "Street, number, stairway, floor, door. Your home address is not your current GPS location."
                "responseInstructions" -> "Agree who acts first, how to verify your safety, and when to call emergency services. Do not assume a call back is safe."
                "backgroundInfo" -> "Relevant medical needs, languages, access instructions or threat context."
                "childrenInfo" -> "Only care details responders need. Avoid full birth dates, school names and daily routines."
                "personDescription" -> "Describe the person sending this alert, not a suspected attacker. Only useful identifying details."
                "profilePhotoUrl" -> "Optional link only; providers and anyone opening it may see the URL. No photo is uploaded here."
                else -> null
            }
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_FILLED
            boxBackgroundColor = color(com.google.android.material.R.attr.colorSurfaceContainerHighest, Color.LTGRAY)
            boxStrokeColor = color(com.google.android.material.R.attr.colorOutline, Color.GRAY)
            setBoxCornerRadii(
                dp(16).toFloat(),
                dp(16).toFloat(),
                dp(16).toFloat(),
                dp(16).toFloat(),
            )
            if (secret) endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            addView(field, matchWidth())
        }, matchWidth(topMargin = dp(12)))
    }

    private fun populate(config: DirectConfig) {
        haptics.isChecked = config.hapticFeedback
        fields.getValue("grafanaWebhookUrl").setText(config.grafanaWebhookUrl.orEmpty())
        fields.getValue("pushoverUserKey").setText(config.pushoverUserKey.orEmpty())
        fields.getValue("pushoverApiToken").setText(config.pushoverApiToken.orEmpty())
        fields.getValue("protectedPersonName").setText(config.protectedPersonName)
        fields.getValue("customAlertMessage").setText(config.customAlertMessage)
        fields.getValue("homeAddress").setText(config.homeAddress)
        fields.getValue("childrenInfo").setText(config.childrenInfo)
        fields.getValue("personDescription").setText(config.personDescription)
        fields.getValue("backgroundInfo").setText(config.backgroundInfo)
        fields.getValue("responseInstructions").setText(config.responseInstructions)
        fields.getValue("profilePhotoUrl").setText(config.profilePhotoUrl)
    }

    private fun value(key: String): String = fields.getValue(key).text.toString().trim()

    private fun String.nullIfBlank(): String? = takeUnless(String::isBlank)

    private fun showWearStatus(message: String) {
        runOnUiThread {
            val normalized = message.lowercase()
            val ready = normalized.contains("confirmed on watch") && normalized.contains("route ready")
            val waiting = normalized.contains("waiting") || normalized.contains("queued")
            val background: Int
            val foreground: Int
            when {
                ready -> {
                    statusTitle.setText(R.string.watch_ready)
                    statusIndicator.text = "✓"
                    background = color(
                        com.google.android.material.R.attr.colorTertiaryContainer,
                        Color.rgb(251, 223, 166),
                    )
                    foreground = color(
                        com.google.android.material.R.attr.colorOnTertiaryContainer,
                        Color.rgb(37, 26, 0),
                    )
                }
                waiting -> {
                    statusTitle.setText(R.string.watch_waiting)
                    statusIndicator.text = "…"
                    background = color(
                        com.google.android.material.R.attr.colorSecondaryContainer,
                        Color.rgb(255, 218, 217),
                    )
                    foreground = color(
                        com.google.android.material.R.attr.colorOnSecondaryContainer,
                        Color.rgb(46, 21, 22),
                    )
                }
                else -> {
                    statusTitle.setText(R.string.watch_attention)
                    statusIndicator.text = "!"
                    background = color(
                        com.google.android.material.R.attr.colorErrorContainer,
                        Color.rgb(255, 218, 214),
                    )
                    foreground = color(
                        com.google.android.material.R.attr.colorOnErrorContainer,
                        Color.rgb(65, 0, 2),
                    )
                }
            }
            statusCard.setCardBackgroundColor(background)
            statusTitle.setTextColor(foreground)
            status.setTextColor(foreground)
            statusIndicator.setTextColor(foreground)
            statusIndicator.background = circle(foreground, background)
            status.text = message
        }
    }

    private fun showGarminStatus(linkStatus: GarminLinkStatus) {
        runOnUiThread {
            val ready = linkStatus is GarminLinkStatus.Ready
            val waiting = linkStatus is GarminLinkStatus.Waiting || linkStatus is GarminLinkStatus.Unavailable
            val background: Int
            val foreground: Int
            when {
                ready -> {
                    garminStatusTitle.text = if (garminLink.connectedWatchName != null) "Connected · Synced" else "Setup confirmed"
                    garminStatusIndicator.text = "✓"
                    background = color(com.google.android.material.R.attr.colorTertiaryContainer, Color.rgb(251, 223, 166))
                    foreground = color(com.google.android.material.R.attr.colorOnTertiaryContainer, Color.rgb(37, 26, 0))
                }
                waiting -> {
                    garminStatusTitle.text = if (garminLink.connectedWatchName != null) "Connected · Sync pending" else "Connection pending"
                    garminStatusIndicator.text = "…"
                    background = color(com.google.android.material.R.attr.colorSecondaryContainer, Color.rgb(255, 218, 217))
                    foreground = color(com.google.android.material.R.attr.colorOnSecondaryContainer, Color.rgb(46, 21, 22))
                }
                else -> {
                    garminStatusTitle.setText(R.string.watch_attention)
                    garminStatusIndicator.text = "!"
                    background = color(com.google.android.material.R.attr.colorErrorContainer, Color.rgb(255, 218, 214))
                    foreground = color(com.google.android.material.R.attr.colorOnErrorContainer, Color.rgb(65, 0, 2))
                }
            }
            garminStatusCard.setCardBackgroundColor(background)
            garminStatusTitle.setTextColor(foreground)
            garminStatus.setTextColor(foreground)
            garminStatusIndicator.setTextColor(foreground)
            garminStatusIndicator.background = circle(foreground, background)
            garminStatus.text = listOfNotNull(garminLink.connectedWatchName, linkStatus.description).joinToString("\n\n")
        }
    }

    private fun statusIndicator(color: Int) = MaterialTextView(this).apply {
        gravity = Gravity.CENTER
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
        setTextColor(color)
    }

    private fun statusTitle(color: Int) = MaterialTextView(this).apply {
        setTextAppearance(R.style.TextAppearance_OpenDistress_Status)
        setTextColor(color)
    }

    private fun statusBody(color: Int) = MaterialTextView(this).apply {
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
        setTextColor(color)
        setLineSpacing(0f, 1.08f)
        accessibilityLiveRegion = MaterialTextView.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private fun statusCard(
        indicator: MaterialTextView,
        title: MaterialTextView,
        body: MaterialTextView,
    ): MaterialCardView {
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(title, matchWidth())
            addView(body, matchWidth(topMargin = dp(4)))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            addView(indicator, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(14) })
            addView(copy, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        return MaterialCardView(this).apply {
            radius = dp(24).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            addView(row, matchWidth())
        }
    }

    private fun changeLocationAssist(enabled: Boolean) {
        if (changingLocationSwitch || !::garminLink.isInitialized) return
        if (!enabled) {
            garminLink.setLocationAssistEnabled(false)
            return
        }
        if (hasFineLocation()) {
            garminLink.setLocationAssistEnabled(true)
        } else {
            changingLocationSwitch = true
            locationAssist.isChecked = false
            changingLocationSwitch = false
            requestPermissions(
                arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION),
                LOCATION_PERMISSION_REQUEST,
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != LOCATION_PERMISSION_REQUEST) return
        val granted = permissions.indices.any {
            permissions[it] == Manifest.permission.ACCESS_FINE_LOCATION &&
                grantResults.getOrNull(it) == PackageManager.PERMISSION_GRANTED
        }
        garminLink.setLocationAssistEnabled(granted)
        changingLocationSwitch = true
        locationAssist.isChecked = granted
        changingLocationSwitch = false
        if (!granted) showGarminStatus(GarminLinkStatus.Attention("Phone location assist is off — location permission was not granted"))
    }

    private fun hasFineLocation(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun circle(stroke: Int, fill: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
        setStroke(dp(2), stroke)
    }

    private fun matchWidth(topMargin: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { this.topMargin = topMargin }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun color(attribute: Int, fallback: Int): Int =
        MaterialColors.getColor(this, attribute, fallback)

    private companion object {
        const val SYNC_BUTTON_ID = 0x0d150001
        const val LOCATION_PERMISSION_REQUEST = 0x0d15
    }
}
