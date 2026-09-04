// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import dev.opendistress.shared.DirectConfig

class MainActivity : Activity(), DataClient.OnDataChangedListener {
    private lateinit var coordinator: ProvisioningCoordinator
    private lateinit var status: TextView
    private lateinit var save: Button
    private val fields = linkedMapOf<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildInterface()
        coordinator = try {
            ProvisioningCoordinator(this)
        } catch (_: Exception) {
            status.setText(R.string.storage_authentication_failed)
            save.isEnabled = false
            return
        }
        coordinator.snapshot().config?.let(::populate)
        status.text = coordinator.statusDescription()
        save.setOnClickListener { saveConfiguration() }
        findViewById<Button>(SYNC_BUTTON_ID).setOnClickListener {
            coordinator.synchronize(::showStatus, force = true)
        }
    }

    override fun onStart() {
        super.onStart()
        if (::coordinator.isInitialized) {
            Wearable.getDataClient(this).addListener(this)
            coordinator.synchronize(::showStatus)
        }
    }

    override fun onStop() {
        if (::coordinator.isInitialized) Wearable.getDataClient(this).removeListener(this)
        super.onStop()
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        coordinator.handleEvents(dataEvents, ::showStatus)
    }

    private fun saveConfiguration() {
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
                responseInstructions = value("responseInstructions"),
                profilePhotoUrl = value("profilePhotoUrl"),
            )
        } catch (error: IllegalArgumentException) {
            showStatus(error.message ?: "Configuration is invalid")
            return
        }
        try {
            coordinator.save(config, ::showStatus)
        } catch (_: Exception) {
            showStatus("Configuration could not be stored securely — nothing was sent")
        }
    }

    private fun buildInterface() {
        val density = resources.displayMetrics.density
        val padding = (20 * density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            setText(R.string.setup_title)
            textSize = 24f
            setTextColor(Color.rgb(16, 24, 32))
        })
        content.addView(TextView(this).apply {
            setText(R.string.setup_explanation)
            textSize = 15f
            setPadding(0, (8 * density).toInt(), 0, padding)
        })

        addField(content, "grafanaWebhookUrl", R.string.grafana_webhook, 512, secret = true)
        addField(content, "pushoverUserKey", R.string.pushover_user_key, 30, secret = true)
        addField(content, "pushoverApiToken", R.string.pushover_api_token, 30, secret = true)
        addSection(content, R.string.emergency_card)
        addField(content, "protectedPersonName", R.string.protected_person_name, 40)
        addField(content, "customAlertMessage", R.string.prepared_alert_message, 240, multiline = true)
        addField(content, "homeAddress", R.string.home_address, 120, multiline = true)
        addField(content, "childrenInfo", R.string.children_information, 150, multiline = true)
        addField(content, "personDescription", R.string.person_description, 150, multiline = true)
        addField(content, "backgroundInfo", R.string.background_information, 180, multiline = true)
        addField(content, "responseInstructions", R.string.response_instructions, 180, multiline = true)
        addField(content, "profilePhotoUrl", R.string.profile_photo_url, 512)

        status = TextView(this).apply {
            textSize = 15f
            setPadding(0, padding, 0, padding / 2)
            accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(status)
        save = Button(this).apply {
            setText(R.string.save_and_send)
            minHeight = (52 * density).toInt()
        }
        content.addView(save, matchWidth())
        content.addView(Button(this).apply {
            id = SYNC_BUTTON_ID
            setText(R.string.retry_sync)
        }, matchWidth())
        content.addView(TextView(this).apply {
            setText(R.string.readiness_explanation)
            textSize = 13f
            setPadding(0, padding / 2, 0, padding)
        })

        setContentView(ScrollView(this).apply { addView(content) })
    }

    private fun addSection(parent: LinearLayout, label: Int) {
        parent.addView(TextView(this).apply {
            setText(label)
            textSize = 20f
            setPadding(0, 28, 0, 6)
        })
    }

    private fun addField(
        parent: LinearLayout,
        key: String,
        label: Int,
        maxLength: Int,
        secret: Boolean = false,
        multiline: Boolean = false,
    ) {
        parent.addView(TextView(this).apply {
            setText(label)
            textSize = 14f
            setPadding(0, 12, 0, 2)
        })
        val field = EditText(this).apply {
            setHint(label)
            filters = arrayOf(InputFilter.LengthFilter(maxLength))
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
            if (multiline) {
                minLines = 2
                gravity = Gravity.TOP or Gravity.START
            }
            importantForAutofill = if (secret) EditText.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS else
                EditText.IMPORTANT_FOR_AUTOFILL_AUTO
        }
        fields[key] = field
        parent.addView(field, matchWidth())
    }

    private fun populate(config: DirectConfig) {
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

    private fun showStatus(message: String) {
        runOnUiThread { status.text = message }
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    )

    private companion object {
        const val SYNC_BUTTON_ID = 0x0d150001
    }
}
