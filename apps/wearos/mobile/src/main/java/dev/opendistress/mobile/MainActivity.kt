// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.Wearable
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import com.google.android.material.color.MaterialColors
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import dev.opendistress.shared.DirectConfig

class MainActivity : Activity(), DataClient.OnDataChangedListener {
    private lateinit var coordinator: ProvisioningCoordinator
    private lateinit var status: TextView
    private lateinit var save: Button
    private val fields = linkedMapOf<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
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
        val padding = dp(20)
        val surface = color(com.google.android.material.R.attr.colorSurface, Color.WHITE)
        val onSurface = color(com.google.android.material.R.attr.colorOnSurface, Color.rgb(36, 25, 26))
        val primary = color(androidx.appcompat.R.attr.colorPrimary, Color.rgb(140, 29, 39))
        val outline = color(com.google.android.material.R.attr.colorOutline, Color.rgb(133, 115, 116))
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        content.addView(TextView(this).apply {
            setText(R.string.setup_eyebrow)
            textSize = 12f
            letterSpacing = 0.12f
            setTextColor(primary)
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        })
        content.addView(TextView(this).apply {
            setText(R.string.setup_title)
            textSize = 32f
            setTextColor(onSurface)
            typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
            setPadding(0, dp(6), 0, 0)
        })
        content.addView(TextView(this).apply {
            setText(R.string.setup_explanation)
            textSize = 16f
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY))
            setLineSpacing(0f, 1.12f)
            setPadding(0, dp(8), 0, dp(24))
        })

        val delivery = addSection(
            content,
            R.string.delivery_title,
            R.string.delivery_explanation,
        )
        addField(delivery, "grafanaWebhookUrl", R.string.grafana_webhook, 512, secret = true)
        addField(delivery, "pushoverUserKey", R.string.pushover_user_key, 30, secret = true)
        addField(delivery, "pushoverApiToken", R.string.pushover_api_token, 30, secret = true)

        val emergency = addSection(
            content,
            R.string.emergency_card,
            R.string.emergency_card_explanation,
        )
        addField(emergency, "protectedPersonName", R.string.protected_person_name, 40)
        addField(emergency, "customAlertMessage", R.string.prepared_alert_message, 240, multiline = true)
        addField(emergency, "homeAddress", R.string.home_address, 120, multiline = true)
        addField(emergency, "childrenInfo", R.string.children_information, 150, multiline = true)
        addField(emergency, "personDescription", R.string.person_description, 150, multiline = true)
        addField(emergency, "backgroundInfo", R.string.background_information, 180, multiline = true)
        addField(emergency, "responseInstructions", R.string.response_instructions, 180, multiline = true)
        addField(emergency, "profilePhotoUrl", R.string.profile_photo_url, 512)

        status = TextView(this).apply {
            textSize = 15f
            setTextColor(onSurface)
            setLineSpacing(0f, 1.08f)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        content.addView(MaterialCardView(this).apply {
            radius = dp(20).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = outline
            setCardBackgroundColor(
                color(com.google.android.material.R.attr.colorSurfaceContainerHigh, surface),
            )
            addView(status, matchWidth())
        }, matchWidth(topMargin = dp(20)))
        save = MaterialButton(this).apply {
            setText(R.string.save_and_send)
            minHeight = dp(56)
            cornerRadius = dp(28)
            textSize = 15f
            insetTop = 0
            insetBottom = 0
        }
        content.addView(save, matchWidth(topMargin = dp(16)))
        content.addView(MaterialButton(
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
        content.addView(TextView(this).apply {
            setText(R.string.readiness_explanation)
            textSize = 13f
            setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY))
            setLineSpacing(0f, 1.12f)
            setPadding(dp(4), dp(16), dp(4), dp(32))
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(surface)
            isFillViewport = true
            clipToPadding = true
            addView(content)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        })
    }

    private fun addSection(parent: LinearLayout, label: Int, explanation: Int): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(20))
            addView(TextView(this@MainActivity).apply {
                setText(label)
                textSize = 22f
                setTextColor(color(com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY))
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            })
            addView(TextView(this@MainActivity).apply {
                setText(explanation)
                textSize = 14f
                setLineSpacing(0f, 1.1f)
                setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY))
                setPadding(0, dp(6), 0, dp(6))
            })
        }
        parent.addView(MaterialCardView(this).apply {
            radius = dp(28).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = color(com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY)
            setCardBackgroundColor(
                color(com.google.android.material.R.attr.colorSurfaceContainerLow, Color.WHITE),
            )
            addView(section, matchWidth())
        }, matchWidth(topMargin = if (parent.childCount > 3) dp(16) else 0))
        return section
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
            filters = arrayOf(InputFilter.LengthFilter(maxLength))
            inputType = when {
                secret -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                multiline -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                else -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            }
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
            hint = getString(label)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
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

    private fun matchWidth(topMargin: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { this.topMargin = topMargin }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun color(attribute: Int, fallback: Int): Int =
        MaterialColors.getColor(this, attribute, fallback)

    private companion object {
        const val SYNC_BUTTON_ID = 0x0d150001
    }
}
