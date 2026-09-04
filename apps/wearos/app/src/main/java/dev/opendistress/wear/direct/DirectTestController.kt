// SPDX-License-Identifier: MIT
package dev.opendistress.wear.direct

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.content.ContextCompat
import androidx.wear.remote.interactions.RemoteActivityHelper
import dev.opendistress.shared.DirectConfig
import dev.opendistress.wear.ui.DirectStatusView
import dev.opendistress.wear.ui.PanicHoldView
import dev.opendistress.wear.ui.ProviderAcceptedAnalogView

/** Foreground TEST-mode UI and the pre-acceptance network handoff. */
internal class DirectTestController(private val activity: Activity) {
    private val configStore = EncryptedDirectConfigStore.get(activity)
    private val store = DirectTestStore.get(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val refreshPoll = object : Runnable {
        override fun run() {
            if (!foreground || destroyed) return
            if (renderSignature() != lastRenderSignature) refresh()
            handler.postDelayed(this, UI_REFRESH_MILLIS)
        }
    }
    private var foreground = false
    private var destroyed = false
    private var locationPermissionRequested = false
    private var notificationPermissionRequested = false
    private var analogVisible = false
    private var lastRenderSignature: Any? = null
    private var ambient = false
    private var lowBitAmbient = false
    private var burnInProtectionRequired = false

    fun onCreate() {
        runCatching { store.scrubExpiredLocation(now()) }
        refresh()
        if (store.snapshot() == null) {
            if (!requestLocationPermissionOnce()) requestNotificationPermissionOnce()
        } else {
            requestNotificationPermissionOnce()
        }
    }

    fun onStart() {
        foreground = true
        refresh()
        resumeWork()
        handler.removeCallbacks(refreshPoll)
        if (!ambient) handler.post(refreshPoll)
    }

    fun onStop() {
        foreground = false
        handler.removeCallbacks(refreshPoll)
    }

    fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
    }

    fun onRequestPermissionsResult(requestCode: Int) {
        when (requestCode) {
            REQUEST_LOCATION -> {
                requestNotificationPermissionOnce()
                if (store.snapshot() != null) startTrackingService()
                refresh()
            }
            REQUEST_NOTIFICATIONS -> refresh()
        }
    }

    fun setAmbientMode(
        isAmbient: Boolean,
        lowBitAmbient: Boolean = false,
        burnInProtectionRequired: Boolean = false,
    ) {
        ambient = isAmbient
        this.lowBitAmbient = lowBitAmbient
        this.burnInProtectionRequired = burnInProtectionRequired
        if (isAmbient && store.snapshot()?.acceptedAt != null && currentAnalogView() == null) {
            showAnalog()
        }
        currentAnalogView()?.setAmbientMode(isAmbient, lowBitAmbient, burnInProtectionRequired)
        handler.removeCallbacks(refreshPoll)
        if (!isAmbient && foreground) handler.post(refreshPoll)
    }

    fun requestAmbientRedraw() {
        currentAnalogView()?.invalidate()
    }

    private fun refresh() {
        lastRenderSignature = renderSignature()
        val config = installedConfig()
        if (config == null) {
            showStatus(
                "SETUP REQUIRED",
                listOf("Install and configure", "OpenDistress on Android", "No alert was sent"),
                "PHONE SETUP",
                ::openPhoneSetup,
            )
            return
        }
        val state = store.snapshot()
        when {
            state == null -> showReady()
            state.isResetPending() -> showStatus(
                "RESET PENDING",
                listOf("Stopping Pushover retries", "TEST retained until confirmed"),
                "RETRY RESET",
                ::startTrackingService,
            )
            state.acceptedAt != null -> showAnalog()
            now() >= state.triggerExpiresAt -> showStatus(
                "TEST EXPIRED",
                listOf("No provider acceptance", "Result remains unknown"),
                "ARCHIVE",
            ) {
                runCatching { store.archiveExpiredTest(now()) }
                refresh()
            }
            state.queue.isEmpty() && state.routes.all { it.status == DirectRouteStatus.REJECTED } -> showStatus(
                "CONFIG ERROR",
                listOf("Providers rejected TEST", "Check phone settings"),
                "RESET TEST",
            ) {
                runCatching { store.resetDefinitivelyRejectedTest() }
                refresh()
            }
            state.queue.isNotEmpty() && store.nextRequest(config, now()) == null -> showStatus(
                "ROUTE CHANGED",
                listOf("Restore phone settings", "Stored TEST retained"),
            )
            else -> showStatus(
                "TEST STORED",
                listOf(
                    "Sending to ${pendingProviders(state)}",
                    "Acceptance pending",
                    shortId(state.incidentId),
                ),
                "RETRY",
                ::startTrackingService,
            )
        }
    }

    private fun showReady() {
        analogVisible = false
        val view = PanicHoldView(activity).apply {
            tag = TAG_READY
            listener = object : PanicHoldView.Listener {
                override fun onHapticCue(cue: PanicHoldView.HapticCue) {
                    when (cue) {
                        PanicHoldView.HapticCue.HOLD_STARTED -> vibrate(VibrationEffect.EFFECT_TICK)
                        PanicHoldView.HapticCue.CANCELLED -> vibrate(VibrationEffect.EFFECT_CLICK)
                        PanicHoldView.HapticCue.READY_TO_CONFIRM -> vibrate(VibrationEffect.EFFECT_HEAVY_CLICK)
                        PanicHoldView.HapticCue.CONFIRMED -> Unit
                    }
                }

                override fun onTriggerConfirmed() {
                    beginTest(this@apply)
                }
            }
        }
        activity.setContentView(view)
    }

    private fun beginTest(view: PanicHoldView) {
        val config = installedConfig()
        if (config == null) {
            view.resetToReady()
            showStatus("SETUP REQUIRED", listOf("No confirmed phone config", "No alert was sent"))
            return
        }
        try {
            store.begin(config, now())
            DirectRecoveryWork.ensure(activity)
            vibrate(VibrationEffect.EFFECT_HEAVY_CLICK)
            refresh()
            startTrackingService()
        } catch (_: Exception) {
            view.resetToReady()
            showStatus("NOT STORED", listOf("No network send attempted", "Try again"), "BACK", ::refresh)
            vibrate(VibrationEffect.EFFECT_DOUBLE_CLICK)
        }
    }

    private fun showAnalog() {
        if (analogVisible && activity.findViewById<android.view.View>(android.R.id.content) != null) return
        analogVisible = true
        activity.setContentView(ProviderAcceptedAnalogView(activity).apply {
            tag = TAG_ACCEPTED
            showResetAction = true
            setAmbientMode(ambient, lowBitAmbient, burnInProtectionRequired)
            listener = object : ProviderAcceptedAnalogView.Listener {
                override fun onDetailsRequested() = showDetails()
                override fun onResetRequested() = showResetConfirmation()
            }
        })
    }

    private fun showResetConfirmation() {
        analogVisible = false
        activity.setContentView(PanicHoldView(activity).apply {
            tag = TAG_RESET_CONFIRMATION
            purpose = PanicHoldView.Purpose.RESET_TEST
            listener = object : PanicHoldView.Listener {
                override fun onHapticCue(cue: PanicHoldView.HapticCue) {
                    when (cue) {
                        PanicHoldView.HapticCue.HOLD_STARTED -> vibrate(VibrationEffect.EFFECT_TICK)
                        PanicHoldView.HapticCue.CANCELLED -> vibrate(VibrationEffect.EFFECT_CLICK)
                        PanicHoldView.HapticCue.READY_TO_CONFIRM -> vibrate(VibrationEffect.EFFECT_HEAVY_CLICK)
                        PanicHoldView.HapticCue.CONFIRMED -> Unit
                    }
                }

                override fun onTriggerConfirmed() = resetAcceptedTest()
            }
        })
    }

    private fun showDetails() {
        val state = store.snapshot() ?: return refresh()
        val accepted = state.routes.filter { it.status == DirectRouteStatus.ACCEPTED }
            .joinToString(" + ") { providerLabel(it.provider) }
        val tracking = if (hasLocationPermission()) "GPS tracking active" else "GPS permission missing"
        val notifications = if (hasNotificationPermission()) {
            "Persistent status enabled"
        } else {
            "Notifications off · status may be hidden"
        }
        showStatus(
            "TEST DETAILS",
            listOf(
                "$accepted accepted",
                "Delivery not confirmed",
                tracking,
                notifications,
                "GPS updates: ${state.nextLocationSequence - 1}",
            ),
            "BACK",
            ::showAnalog,
        )
    }

    private fun resetAcceptedTest() {
        val config = installedConfig()
        if (config == null) {
            showStatus("RESET FAILED", listOf("Phone configuration unavailable", "Accepted TEST retained"), "BACK", ::showAnalog)
            return
        }
        val cleared = runCatching { store.requestAcceptedTestReset(config, now()) }.getOrElse {
            showStatus("RESET FAILED", listOf("Accepted TEST retained"), "BACK", ::showAnalog)
            return
        }
        analogVisible = false
        if (cleared) {
            DirectRecoveryWork.cancel(activity)
            DirectTrackingService.stop(activity)
            showReady()
        } else {
            DirectRecoveryWork.ensure(activity)
            refresh()
            startTrackingService()
        }
    }

    private fun showStatus(
        title: String,
        lines: List<String>,
        action: String? = null,
        callback: (() -> Unit)? = null,
    ) {
        analogVisible = false
        activity.setContentView(DirectStatusView(activity).apply {
            tag = TAG_STATUS
            this.title = title
            this.lines = lines
            actionLabel = action
            onAction = callback
        })
    }

    private fun resumeWork() {
        if (store.snapshot() != null) startTrackingService()
    }

    private fun startTrackingService() {
        runCatching { DirectTrackingService.start(activity) }
    }

    private fun openPhoneSetup() {
        val executor = ContextCompat.getMainExecutor(activity)
        val helper = RemoteActivityHelper(activity.applicationContext, executor)
        val intent = Intent(Intent.ACTION_VIEW)
            .setData(Uri.parse("https://play.google.com/store/apps/details?id=${activity.packageName}"))
            .addCategory(Intent.CATEGORY_BROWSABLE)
        val future = runCatching { helper.startRemoteActivity(intent, null) }.getOrElse {
            showStatus("PHONE UNAVAILABLE", listOf("Pair an Android phone", "Then try again"), "BACK", ::refresh)
            return
        }
        future.addListener(
            {
                runCatching { future.get() }.onFailure {
                    showStatus(
                        "PHONE UNAVAILABLE",
                        listOf("Pair an Android phone", "Or sideload phone APK"),
                        "BACK",
                        ::refresh,
                    )
                }
            },
            executor,
        )
    }

    private fun renderSignature(): Any? = runCatching {
        val configRevision = installedConfig()?.revision
        val state = store.snapshot()
        listOf(
            configRevision,
            state?.incidentId,
            state?.acceptedAt,
            state?.trackingExpiresAt,
            state?.routes,
            state?.queue?.map { it.requestId },
            state?.nextLocationSequence,
        )
    }.getOrNull()

    private fun currentAnalogView(): ProviderAcceptedAnalogView? {
        val content = activity.findViewById<android.view.ViewGroup>(android.R.id.content)
        return content?.getChildAt(0) as? ProviderAcceptedAnalogView
    }

    private fun requestLocationPermissionOnce(): Boolean {
        if (locationPermissionRequested || hasLocationPermission()) return false
        locationPermissionRequested = true
        activity.requestPermissions(
            arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            ),
            REQUEST_LOCATION,
        )
        return true
    }

    private fun requestNotificationPermissionOnce() {
        if (
            Build.VERSION.SDK_INT < 33 ||
            notificationPermissionRequested ||
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        ) return
        notificationPermissionRequested = true
        activity.requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            REQUEST_NOTIFICATIONS,
        )
    }

    private fun hasLocationPermission(): Boolean =
        activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            activity.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun installedConfig(): DirectConfig? = runCatching { configStore.snapshot()?.config }.getOrNull()

    private fun pendingProviders(state: DirectTestState): String = state.routes
        .filter { it.status == DirectRouteStatus.PENDING }
        .joinToString(" + ") { providerLabel(it.provider) }
        .ifEmpty { "provider" }

    private fun providerLabel(provider: DirectProvider): String = when (provider) {
        DirectProvider.GRAFANA -> "Grafana"
        DirectProvider.PUSHOVER -> "Pushover"
    }

    private fun shortId(value: String): String = "ID ${value.take(8)}"

    private fun vibrate(effect: Int) {
        activity.getSystemService(Vibrator::class.java)?.takeIf(Vibrator::hasVibrator)?.vibrate(
            VibrationEffect.createPredefined(effect),
        )
    }

    private fun now(): Long = System.currentTimeMillis() / 1_000

    companion object {
        const val REQUEST_LOCATION = 4_021
        const val REQUEST_NOTIFICATIONS = 4_022
        const val TAG_READY = "direct-ready"
        const val TAG_STATUS = "direct-status"
        const val TAG_ACCEPTED = "direct-accepted"
        const val TAG_RESET_CONFIRMATION = "direct-reset-confirmation"
        private const val UI_REFRESH_MILLIS = 500L
    }
}
