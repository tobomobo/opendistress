// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.EditText
import android.widget.ImageView
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
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textview.MaterialTextView
import dev.opendistress.shared.DirectConfig

class MainActivity : Activity(), DataClient.OnDataChangedListener {
    private lateinit var coordinator: ProvisioningCoordinator
    private lateinit var status: MaterialTextView
    private lateinit var statusTitle: MaterialTextView
    private lateinit var statusIndicator: MaterialTextView
    private lateinit var statusCard: MaterialCardView
    private lateinit var save: MaterialButton
    private val fields = linkedMapOf<String, EditText>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DynamicColors.applyToActivityIfAvailable(this)
        buildInterface()
        coordinator = try {
            ProvisioningCoordinator(this)
        } catch (_: Exception) {
            showStatus(getString(R.string.storage_authentication_failed))
            save.isEnabled = false
            return
        }
        coordinator.snapshot().config?.let(::populate)
        showStatus(coordinator.statusDescription())
        save.setOnClickListener { saveConfiguration() }
        findViewById<MaterialButton>(SYNC_BUTTON_ID).setOnClickListener {
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
        content.addView(heroCard(), matchWidth())

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
        content.addView(MaterialTextView(this).apply {
            setText(R.string.watch_readiness)
            setTextAppearance(R.style.TextAppearance_OpenDistress_Section)
            setTextColor(onSurface)
            setPadding(dp(4), dp(28), dp(4), dp(10))
        }, matchWidth())
        content.addView(statusCard, matchWidth())
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
        content.addView(MaterialTextView(this).apply {
            setText(R.string.readiness_explanation)
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
            setTextColor(onSurfaceVariant)
            setLineSpacing(0f, 1.12f)
            setPadding(dp(4), dp(16), dp(4), 0)
        })

        page.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(page.apply {
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(0, bars.top, 0, bars.bottom)
                insets
            }
        })
    }

    private fun heroCard(): MaterialCardView {
        val onContainer = color(
            com.google.android.material.R.attr.colorOnPrimaryContainer,
            Color.rgb(59, 7, 16),
        )
        val copy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(MaterialTextView(this@MainActivity).apply {
                setText(R.string.setup_eyebrow)
                setTextAppearance(R.style.TextAppearance_OpenDistress_Eyebrow)
                setTextColor(onContainer)
            }, matchWidth())
            addView(MaterialTextView(this@MainActivity).apply {
                setText(R.string.setup_title)
                setTextAppearance(R.style.TextAppearance_OpenDistress_Hero)
                setTextColor(onContainer)
            }, matchWidth(topMargin = dp(6)))
            addView(MaterialTextView(this@MainActivity).apply {
                setText(R.string.setup_explanation)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                setTextColor(onContainer)
                setLineSpacing(0f, 1.12f)
            }, matchWidth(topMargin = dp(8)))
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(22), dp(22), dp(24))
            addView(ImageView(this@MainActivity).apply {
                setImageResource(R.drawable.ic_splash_mark)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                contentDescription = null
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(64), dp(64)).apply { bottomMargin = dp(16) })
            addView(copy, matchWidth())
        }
        return MaterialCardView(this).apply {
            radius = dp(28).toFloat()
            cardElevation = 0f
            strokeWidth = 0
            setCardBackgroundColor(
                color(com.google.android.material.R.attr.colorPrimaryContainer, Color.rgb(255, 218, 217)),
            )
            addView(row, matchWidth())
        }
    }

    private fun addSection(parent: LinearLayout, label: Int, explanation: Int): LinearLayout {
        val section = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(20))
            addView(MaterialTextView(this@MainActivity).apply {
                setText(label)
                setTextAppearance(R.style.TextAppearance_OpenDistress_Section)
                setTextColor(color(com.google.android.material.R.attr.colorOnSurface, Color.DKGRAY))
            })
            addView(MaterialTextView(this@MainActivity).apply {
                setText(explanation)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setLineSpacing(0f, 1.1f)
                setTextColor(color(com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY))
                setPadding(0, dp(6), 0, dp(6))
            })
        }
        parent.addView(MaterialCardView(this).apply {
            radius = dp(28).toFloat()
            cardElevation = 0f
            strokeWidth = 0
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
    }
}
