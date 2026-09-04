// SPDX-License-Identifier: MIT
package dev.opendistress.wear

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.wear.ambient.AmbientLifecycleObserver
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dev.opendistress.wear.direct.DirectTestController
import dev.opendistress.wear.direct.EncryptedDirectConfigStore
import dev.opendistress.wear.ui.DirectStatusView
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal fun foregroundCadenceSeconds(startedAt: Long, now: Long, lowBattery: Boolean): Long {
    val elapsed = (now - startedAt).coerceAtLeast(0)
    val base = when {
        elapsed < 5 * 60 -> 30L
        elapsed < 30 * 60 -> 2 * 60L
        else -> 5 * 60L
    }
    return if (lowBattery) base * 2 else base
}

internal fun nextForegroundCaptureAt(startedAt: Long, now: Long, lowBattery: Boolean): Long =
    (now + foregroundCadenceSeconds(startedAt, now, lowBattery)).coerceAtMost(PROTOCOL_MAX)

internal fun canCaptureLocation(now: Long, expiresAt: Long): Boolean = now < expiresAt

internal fun isMaterialLocation(plan: CapturePlan, point: LocationPoint): Boolean {
    if (point.quality == 0) return false
    if (point.quality > plan.lastQuality) return true
    val latitude = point.latitudeE7 ?: return false
    val longitude = point.longitudeE7 ?: return false
    val lastLatitude = plan.lastLatitudeE7 ?: return true
    val lastLongitude = plan.lastLongitudeE7 ?: return true
    val latitude1 = Math.toRadians(lastLatitude / 10_000_000.0)
    val latitude2 = Math.toRadians(latitude / 10_000_000.0)
    val deltaLatitude = latitude2 - latitude1
    val deltaLongitude = Math.toRadians(
        (longitude.toDouble() - lastLongitude.toDouble()) / 10_000_000.0,
    )
    val a = (
        sin(deltaLatitude / 2) * sin(deltaLatitude / 2) +
            cos(latitude1) * cos(latitude2) *
            sin(deltaLongitude / 2) * sin(deltaLongitude / 2)
        ).coerceIn(0.0, 1.0)
    val meters = 2 * 6_371_000.0 * atan2(sqrt(a), sqrt(1 - a))
    return meters >= 50
}

class MainActivity : ComponentActivity() {
    private var directController: DirectTestController? = null
    private lateinit var ambientObserver: AmbientLifecycleObserver
    private var directStateUnreadable = false
    private lateinit var status: TextView
    private lateinit var trigger: Button
    private var config: RuntimeConfig? = null
    private var store: EventStore? = null
    private var transport: Transport? = null
    private var fused: FusedLocationProviderClient? = null
    private var freshCancellation: CancellationTokenSource? = null
    private var captureInProgress = false
    private var captureGeneration = 0L
    private var isForeground = false
    private var statusInProgress = false
    private var pendingStatusRequestId: String? = null
    private val sending = AtomicBoolean(false)
    private val network = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val retry = Runnable { drainQueue() }
    private val locationTick = Runnable { continueCapture() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ambientObserver = AmbientLifecycleObserver(
            this,
            object : AmbientLifecycleObserver.AmbientLifecycleCallback {
                override fun onEnterAmbient(ambientDetails: AmbientLifecycleObserver.AmbientDetails) {
                    directController?.setAmbientMode(
                        true,
                        ambientDetails.deviceHasLowBitAmbient,
                        ambientDetails.burnInProtectionRequired,
                    )
                }

                override fun onUpdateAmbient() {
                    directController?.requestAmbientRedraw()
                }

                override fun onExitAmbient() {
                    directController?.setAmbientMode(false)
                }
            },
        ).also(lifecycle::addObserver)
        val hasPhoneProvisionedDirectConfig = runCatching {
            EncryptedDirectConfigStore.get(this).snapshot() != null
        }.getOrDefault(false)
        val legacyConfig = runCatching { RuntimeConfig.fromBuildConfig() }.getOrNull()
        if (hasPhoneProvisionedDirectConfig || legacyConfig == null) {
            directController = runCatching { DirectTestController(this).also { it.onCreate() } }
                .getOrElse {
                    directStateUnreadable = true
                    setContentView(DirectStatusView(this).apply {
                        title = "STORED TEST UNREADABLE"
                        lines = listOf("No new alert was sent", "Do not trigger again", "Clear app data to recover")
                    })
                    null
                }
            return
        }
        buildInterface()
        try {
            config = legacyConfig
            store = EventStore.get(this)
            requireNotNull(store).scrubExpiredLocation(nowSeconds())
            transport = Transport(requireNotNull(config))
        } catch (_: Exception) {
            status.text = "Not configured or stored data unreadable — no alert sent"
            trigger.isEnabled = false
            return
        }

        trigger.setOnClickListener { activateOrRetry() }
        val state = requireNotNull(store).snapshot()
        if (state.queue.isEmpty() && state.capturePlan == null) {
            status.text = "Ready — no alert sent"
        } else if (state.queue.isEmpty() && state.capturePlan?.stage == CaptureStage.FOLLOW_UP) {
            status.text = "Incident active — foreground location follow-up scheduled"
        } else {
            status.text = "Stored on watch — relay acceptance pending"
        }
        refreshAction()
        drainQueue()
        continueCapture()
    }

    override fun onStart() {
        super.onStart()
        directController?.let {
            it.onStart()
            return
        }
        if (directStateUnreadable) return
        isForeground = true
        drainQueue()
        continueCapture()
    }

    override fun onStop() {
        directController?.let {
            it.onStop()
            super.onStop()
            return
        }
        if (directStateUnreadable) {
            super.onStop()
            return
        }
        isForeground = false
        stopLocationCapture()
        super.onStop()
    }

    override fun onDestroy() {
        directController?.onDestroy()
        handler.removeCallbacks(retry)
        stopLocationCapture()
        network.shutdownNow()
        super.onDestroy()
    }

    private fun buildInterface() {
        val padding = (resources.displayMetrics.density * 16).toInt()
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(padding, padding, padding, padding)
        }
        trigger = Button(this).apply {
            text = "SEND ALERT"
            textSize = 20f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(180, 20, 30))
            minHeight = (resources.displayMetrics.density * 64).toInt()
        }
        status = TextView(this).apply {
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, padding, 0, 0)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        layout.addView(
            trigger,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        layout.addView(
            status,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        setContentView(layout)
    }

    private fun activateOrRetry() {
        val eventStore = store ?: return
        val now = nowSeconds()
        try {
            eventStore.scrubExpiredLocation(now)
        } catch (_: Exception) {
            status.text = "Expired location could not be scrubbed; no new alert sent"
            return
        }
        if (eventStore.hasExpiredPending(now)) {
            try {
                eventStore.archiveExpired(now)
                stopLocationCapture()
                handler.removeCallbacks(retry)
                status.text = "Expired incident archived — result unknown; no new alert sent"
                playHaptic(VibrationEffect.EFFECT_DOUBLE_CLICK)
                refreshAction()
            } catch (_: Exception) {
                status.text = "Expired incident could not be archived — result unknown"
            }
            return
        }
        val existing = eventStore.snapshot()
        if (existing.queue.isNotEmpty() || existing.capturePlan != null) {
            status.text = "Retrying stored alert — relay acceptance pending"
            drainQueue()
            continueCapture()
            return
        }

        try {
            val live = Protocol.createLive(requireNotNull(config), now)
            eventStore.startIncident(live)
            playHaptic(VibrationEffect.EFFECT_HEAVY_CLICK)
            status.text = "Recognized on this watch — relay acceptance pending"
            drainQueue()
            continueCapture()
        } catch (_: Exception) {
            status.text = "Watch could not persist alert — no network send attempted"
            playHaptic(VibrationEffect.EFFECT_DOUBLE_CLICK)
        }
    }

    private fun continueCapture(statusChecked: Boolean = false) {
        if (captureInProgress || !isForeground || isDestroyed) return
        val plan = store?.snapshot()?.capturePlan ?: return
        val currentConfig = config ?: return
        if (plan.deviceId != currentConfig.deviceId || plan.keyVersion != currentConfig.keyVersion) {
            status.text = "Local key configuration changed — location not queued"
            return
        }
        val now = nowSeconds()
        if (!canCaptureLocation(now, plan.expiresAt)) {
            try {
                store?.scrubExpiredLocation(now)
            } catch (_: Exception) {
                status.text = "Incident expired, but stored location could not be scrubbed"
                return
            }
            status.text = "Incident expired — ARCHIVE EXPIRED to record result unknown"
            refreshAction()
            return
        }
        if (plan.stage == CaptureStage.FOLLOW_UP && now < plan.nextCaptureAt) {
            scheduleLocationTick(minOf(plan.nextCaptureAt, plan.expiresAt) - now)
            return
        }
        if (plan.stage == CaptureStage.FOLLOW_UP && !statusChecked) {
            if (sending.get()) {
                scheduleLocationTick(1)
            } else {
                pollStatus(plan)
            }
            return
        }
        if (!hasLocationPermission()) {
            if (plan.stage == CaptureStage.FOLLOW_UP) {
                try {
                    store?.rescheduleFollowUp(nextCaptureAt(plan, now))
                    status.text = "Foreground location unavailable; next attempt scheduled"
                    continueCapture()
                } catch (_: Exception) {
                    status.text = "Location schedule could not be persisted"
                }
                return
            }
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
                REQUEST_LOCATION,
            )
            status.text = "Alert stored — location permission requested after activation"
            return
        }
        capture(plan)
    }

    @SuppressLint("MissingPermission")
    private fun capture(plan: CapturePlan) {
        captureInProgress = true
        val generation = ++captureGeneration
        val client = fused ?: LocationServices.getFusedLocationProviderClient(this).also { fused = it }
        if (plan.stage == CaptureStage.SNAPSHOT) {
            client.lastLocation.addOnCompleteListener { task ->
                handler.post {
                    if (!isForeground || isDestroyed || generation != captureGeneration) return@post
                    captureInProgress = false
                    if (queueLocation(plan, if (task.isSuccessful) task.result else null)) {
                        continueCapture()
                    }
                }
            }
            return
        }

        val cancellation = CancellationTokenSource()
        freshCancellation = cancellation
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0)
            .setDurationMillis(15_000)
            .build()
        client.getCurrentLocation(request, cancellation.token).addOnCompleteListener { task ->
            handler.post {
                if (!isForeground || isDestroyed || generation != captureGeneration || task.isCanceled) {
                    return@post
                }
                freshCancellation = null
                captureInProgress = false
                if (queueLocation(plan, if (task.isSuccessful) task.result else null)) {
                    continueCapture()
                }
            }
        }
    }

    private fun queueLocation(expectedPlan: CapturePlan, location: Location?): Boolean {
        val eventStore = store ?: return false
        val currentPlan = eventStore.snapshot().capturePlan ?: return false
        if (currentPlan != expectedPlan) return false
        val createdAt = nowSeconds()
        if (!canCaptureLocation(createdAt, currentPlan.expiresAt)) {
            try {
                eventStore.scrubExpiredLocation(createdAt)
            } catch (_: Exception) {
                status.text = "Incident expired, but stored location could not be scrubbed"
                return false
            }
            status.text = "Incident expired — ARCHIVE EXPIRED to record result unknown"
            refreshAction()
            return false
        }
        val sample = locationSample(location, currentPlan.stage, createdAt)
        try {
            val point = Protocol.locationPoint(sample)
            val nextCaptureAt = nextCaptureAt(currentPlan, createdAt)
            if (currentPlan.stage == CaptureStage.FOLLOW_UP && !isMaterialLocation(currentPlan, point)) {
                eventStore.rescheduleFollowUp(nextCaptureAt)
                status.text = "Location unchanged; foreground follow-up rescheduled"
                return true
            }
            val event = Protocol.createLocation(
                config = requireNotNull(config),
                incidentId = currentPlan.incidentId,
                sequence = currentPlan.nextSequence,
                createdAt = createdAt,
                expiresAt = currentPlan.expiresAt,
                sample = sample,
            )
            if (currentPlan.stage == CaptureStage.SNAPSHOT) {
                eventStore.appendSnapshot(event, point)
                status.text = "Cached location encrypted and queued after live alert"
            } else if (currentPlan.stage == CaptureStage.FRESH) {
                eventStore.appendFresh(event, point, nextCaptureAt)
                status.text = "Fresh location attempt encrypted and queued after live alert"
            } else {
                eventStore.appendFollowUp(event, point, nextCaptureAt)
                status.text = "Material location update encrypted and queued"
            }
            drainQueue()
            return true
        } catch (_: Exception) {
            status.text = "Location could not be persisted; live alert remains queued"
            playHaptic(VibrationEffect.EFFECT_DOUBLE_CLICK)
            return false
        }
    }

    private fun locationSample(location: Location?, stage: CaptureStage, createdAt: Long): LocationSample {
        val path = if (stage == CaptureStage.SNAPSHOT) 0 else 1
        if (
            location == null ||
            !location.latitude.isFinite() ||
            !location.longitude.isFinite() ||
            location.latitude !in -90.0..90.0 ||
            location.longitude !in -180.0..180.0 ||
            location.time <= 0
        ) {
            return LocationSample(0, null, null, 0, path)
        }
        val captureAt = location.time / 1000
        if (captureAt !in 1..createdAt) {
            return LocationSample(0, null, null, 0, path)
        }
        val quality = if (stage == CaptureStage.SNAPSHOT) {
            1
        } else {
            when {
                !location.hasAccuracy() -> 2
                location.accuracy <= 20f -> 4
                location.accuracy <= 100f -> 3
                else -> 2
            }
        }
        return LocationSample(captureAt, location.latitude, location.longitude, quality, path)
    }

    private fun queueUnavailableLocations() {
        while (true) {
            val plan = store?.snapshot()?.capturePlan ?: return
            if (plan.stage == CaptureStage.FOLLOW_UP) {
                continueCapture()
                return
            }
            queueLocation(plan, null)
            if (store?.snapshot()?.capturePlan == plan) return
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        directController?.let {
            it.onRequestPermissionsResult(requestCode)
            return
        }
        if (requestCode != REQUEST_LOCATION) return
        if (hasLocationPermission()) {
            continueCapture()
        } else {
            status.text = "Location unavailable after activation; encrypted unavailable records queued"
            queueUnavailableLocations()
        }
    }

    private fun drainQueue() {
        val eventStore = store ?: return
        if (statusInProgress) return
        val event = eventStore.snapshot().queue.firstOrNull() ?: return
        val now = nowSeconds()
        if (now >= event.expiresAt) {
            try {
                eventStore.scrubExpiredLocation(now)
            } catch (_: Exception) {
                status.text = "Stored event expired, but location could not be scrubbed"
                return
            }
        }
        if (now >= event.expiresAt) {
            status.text = "Stored event expired — ARCHIVE EXPIRED to record result unknown"
            refreshAction()
            return
        }
        if (!sending.compareAndSet(false, true)) return
        try {
            network.execute {
                val outcome = try {
                    requireNotNull(transport).send(event)
                } catch (_: Exception) {
                    SendOutcome(false, "Stored on watch — relay acceptance pending (network unavailable)")
                }
                handler.post {
                    if (isDestroyed) {
                        sending.set(false)
                        return@post
                    }
                    sending.set(false)
                    if (eventStore.snapshot().queue.firstOrNull()?.eventId != event.eventId) {
                        return@post
                    }
                    if (!outcome.accepted) {
                        status.text = outcome.label
                        handler.removeCallbacks(retry)
                        handler.postDelayed(retry, RETRY_MILLIS)
                        return@post
                    }
                    try {
                        if (eventStore.removeMatchingHead(event.eventId)) {
                            status.text = outcome.label
                            playHaptic(VibrationEffect.EFFECT_CLICK)
                            refreshAction()
                            drainQueue()
                            continueCapture()
                        } else {
                            status.text = "Relay evidence matched an event that is no longer queue head"
                            handler.postDelayed(retry, RETRY_MILLIS)
                        }
                    } catch (_: Exception) {
                        status.text = "Relay evidence verified, but the stored event could not be cleared"
                        handler.postDelayed(retry, RETRY_MILLIS)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            sending.set(false)
        }
    }

    private fun pollStatus(expectedPlan: CapturePlan) {
        if (statusInProgress || !isForeground || isDestroyed) return
        val eventStore = store ?: return
        val currentPlan = eventStore.snapshot().capturePlan ?: return
        if (currentPlan != expectedPlan) return
        val now = nowSeconds()
        if (!canCaptureLocation(now, currentPlan.expiresAt)) {
            continueCapture(statusChecked = true)
            return
        }
        val query = try {
            Protocol.createStatusQuery(requireNotNull(config), currentPlan, now)
        } catch (_: Exception) {
            continueCapture(statusChecked = true)
            return
        }
        statusInProgress = true
        pendingStatusRequestId = query.requestId
        try {
            network.execute {
                val outcome = try {
                    requireNotNull(transport).sendStatus(query)
                } catch (_: Exception) {
                    StatusPollOutcome(null)
                }
                handler.post {
                    if (
                        !isForeground ||
                        isDestroyed ||
                        pendingStatusRequestId != query.requestId
                    ) {
                        return@post
                    }
                    statusInProgress = false
                    pendingStatusRequestId = null
                    if (eventStore.snapshot().capturePlan != expectedPlan) return@post
                    val verified = outcome.verified
                    if (verified?.state == "resolved" || verified?.state == "expired") {
                        try {
                            if (eventStore.archiveVerifiedTerminalIncident(query.incidentId)) {
                                stopLocationCapture()
                                handler.removeCallbacks(retry)
                                status.text =
                                    "Verified incident ${verified.state} — queued retransmissions archived"
                                playHaptic(VibrationEffect.EFFECT_CLICK)
                                refreshAction()
                            }
                        } catch (_: Exception) {
                            status.text =
                                "Verified incident status could not be persisted; location continues"
                            drainQueue()
                            continueCapture(statusChecked = true)
                        }
                        return@post
                    }
                    if (verified?.state == "acknowledged") {
                        status.text = "Human acknowledgement verified — incident remains active"
                    }
                    drainQueue()
                    continueCapture(statusChecked = true)
                }
            }
        } catch (_: RejectedExecutionException) {
            if (pendingStatusRequestId == query.requestId) {
                statusInProgress = false
                pendingStatusRequestId = null
            }
            continueCapture(statusChecked = true)
        }
    }

    private fun nextCaptureAt(plan: CapturePlan, now: Long): Long =
        nextForegroundCaptureAt(plan.startedAt, now, isLowBattery())

    private fun scheduleLocationTick(seconds: Long) {
        handler.removeCallbacks(locationTick)
        handler.postDelayed(locationTick, seconds.coerceAtLeast(1) * 1000)
    }

    private fun stopLocationCapture() {
        captureGeneration++
        handler.removeCallbacks(locationTick)
        freshCancellation?.cancel()
        freshCancellation = null
        captureInProgress = false
        statusInProgress = false
        pendingStatusRequestId = null
    }

    private fun isLowBattery(): Boolean {
        val level = getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?: -1
        return level in 0..20
    }

    private fun refreshAction() {
        trigger.text = if (store?.hasExpiredPending(nowSeconds()) == true) {
            "ARCHIVE EXPIRED — RESULT UNKNOWN"
        } else {
            "SEND / RETRY ALERT"
        }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun playHaptic(effect: Int) {
        getSystemService(Vibrator::class.java)?.let { vibrator ->
            if (vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createPredefined(effect))
            }
        }
    }

    private fun nowSeconds(): Long = System.currentTimeMillis() / 1000

    private companion object {
        const val REQUEST_LOCATION = 401
        const val RETRY_MILLIS = 30_000L
    }
}
