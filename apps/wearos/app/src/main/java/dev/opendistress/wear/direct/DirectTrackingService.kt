// SPDX-License-Identifier: MIT
package dev.opendistress.wear.direct

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.wear.ongoing.OngoingActivity
import androidx.wear.ongoing.Status
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dev.opendistress.shared.DirectConfig
import dev.opendistress.wear.MainActivity
import dev.opendistress.wear.R
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

internal fun directTrackingCadenceSeconds(acceptedAt: Long, now: Long, lowBattery: Boolean): Long {
    val elapsed = (now - acceptedAt).coerceAtLeast(0)
    val base = when {
        elapsed < 5 * 60 -> 30L
        elapsed < 30 * 60 -> 2 * 60L
        else -> 5 * 60L
    }
    return if (lowBattery) base * 2 else base
}

internal fun isUsableDirectLocation(
    latitude: Double,
    longitude: Double,
    capturedAt: Long,
    observedAt: Long,
    isMock: Boolean,
): Boolean = !isMock &&
    latitude.isFinite() && latitude in -90.0..90.0 &&
    longitude.isFinite() && longitude in -180.0..180.0 &&
    capturedAt in 1..observedAt

internal object DirectNetworkGate {
    val sending = AtomicBoolean(false)
    val serviceActive = AtomicBoolean(false)
}

/**
 * Continues accepted direct TEST delivery and best-effort fused-location updates for 24 hours.
 * The persistent notification is intentionally honest: provider acceptance is not delivery.
 */
internal class DirectTrackingService : Service() {
    private lateinit var configStore: EncryptedDirectConfigStore
    private lateinit var store: DirectTestStore
    private lateinit var fused: FusedLocationProviderClient
    private val handler = Handler(Looper.getMainLooper())
    private val network = Executors.newSingleThreadExecutor()
    private val transport = DirectProviderTransport()
    private var cancellation: CancellationTokenSource? = null
    private var captureInProgress = false
    private var locationUpdatesIncident: String? = null
    private var locationUpdatesIntervalMillis = 0L
    private var locationCallback: LocationCallback? = null
    private var stopped = false
    private var retryWakeLock: PowerManager.WakeLock? = null
    private val work = Runnable { work() }
    private val expiryCheck = Runnable { work() }

    override fun onCreate() {
        super.onCreate()
        configStore = EncryptedDirectConfigStore.get(this)
        store = DirectTestStore.get(this)
        fused = LocationServices.getFusedLocationProviderClient(this)
        DirectNetworkGate.serviceActive.set(true)
        ensureChannel()
        promoteForeground(store.snapshot()?.acceptedAt != null && hasLocationPermission())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopped = true
            handler.removeCallbacksAndMessages(null)
            cancellation?.cancel()
            stopLocationUpdates()
            releaseRetryWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        stopped = false
        handler.removeCallbacks(work)
        handler.post(work)
        return START_STICKY
    }

    override fun onDestroy() {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        cancellation?.cancel()
        stopLocationUpdates()
        releaseRetryWakeLock()
        network.shutdownNow()
        DirectNetworkGate.serviceActive.set(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun work() {
        if (stopped || captureInProgress) return
        if (DirectNetworkGate.sending.get()) {
            schedule(1)
            return
        }
        val config = configStore.snapshot()?.config ?: return finish("Configuration unavailable")
        val state = store.snapshot() ?: return finish("TEST reset")
        val now = now()
        val acceptedAt = state.acceptedAt
        if (acceptedAt == null) {
            holdRetryWakeLock(state.triggerExpiresAt, now)
            stopLocationUpdates()
            promoteForeground(false)
            if (now >= state.triggerExpiresAt) return finish("TEST expired · result unknown")
            scheduleExpiryCheck(state.triggerExpiresAt - now)
            val request = store.nextRequest(config, now)
            if (request != null) {
                send(request)
                return
            }
            if (state.queue.isNotEmpty()) {
                updateNotification("Route changed · stored TEST retained")
                schedule(minOf(ROUTE_RECHECK_SECONDS, state.triggerExpiresAt - now))
            } else {
                finish("Providers rejected TEST")
            }
            return
        }

        if (state.isResetPending()) {
            holdRetryWakeLock(state.pushoverEmergencyRepeatsUntil(), now)
            stopLocationUpdates()
            promoteForeground(false)
            if (now >= state.pushoverEmergencyRepeatsUntil()) {
                runCatching { store.completeResetAfterEmergencyExpiry(now) }
                return finish("TEST reset · emergency retries expired")
            }
            val cancellation = store.nextRequest(config, now)
            if (cancellation != null) {
                send(cancellation)
            } else {
                updateNotification("Reset pending · restore Pushover settings")
                schedule(minOf(ROUTE_RECHECK_SECONDS, state.pushoverEmergencyRepeatsUntil() - now))
            }
            return
        }

        val expiresAt = state.trackingExpiresAt ?: return finish("Tracking state unavailable")
        releaseRetryWakeLock()
        promoteForeground(hasLocationPermission())
        if (now >= expiresAt) {
            runCatching { store.scrubExpiredLocation(now) }
            return finish("24-hour GPS window ended")
        }
        scheduleExpiryCheck(expiresAt - now)

        val request = store.nextRequest(config, now)
        if (request != null) {
            send(request)
            return
        }
        if (!hasLocationPermission()) return finish("Provider accepted · GPS permission missing")
        if (!store.hasAcceptedLocationTarget(config)) {
            stopLocationUpdates()
            updateNotification("Accepted route changed · GPS update retained locally")
            schedule(ROUTE_RECHECK_SECONDS)
            return
        }

        val schedule = TrackingSchedule(this, state.incidentId)
        when {
            !schedule.cachedAttempted -> {
                schedule.cachedAttempted = true
                captureCached(config, state.incidentId)
            }
            schedule.nextCaptureAt == 0L -> captureFresh(config, state.incidentId, acceptedAt, schedule)
            else -> ensureLocationUpdates(state.incidentId, acceptedAt)
        }
    }

    private fun send(request: DirectHttpRequest) {
        if (!DirectNetworkGate.sending.compareAndSet(false, true)) {
            schedule(1)
            return
        }
        updateNotification(
            when (request.kind) {
                DirectRequestKind.LOCATION -> "Sending GPS update"
                DirectRequestKind.TRIGGER -> "Sending provider fallback"
                DirectRequestKind.CANCEL -> "Canceling Pushover emergency retries"
            },
        )
        try {
            network.execute {
                val outcome = transport.send(request)
                handler.post {
                    DirectNetworkGate.sending.set(false)
                    if (stopped || store.snapshot()?.incidentId != request.incidentId) return@post
                    try {
                        when {
                            outcome.acceptance != null && request.kind == DirectRequestKind.TRIGGER -> {
                                val firstAcceptance = store.snapshot()?.acceptedAt == null
                                store.recordTriggerAccepted(request.requestId, outcome.acceptance, now())
                                if (firstAcceptance) vibrateAccepted()
                            }
                            outcome.acceptance != null && request.kind == DirectRequestKind.LOCATION ->
                                store.recordLocationAccepted(request.requestId)
                            outcome.acceptance != null && request.kind == DirectRequestKind.CANCEL ->
                                store.recordCancellationAccepted(request.requestId, outcome.acceptance)
                            request.kind == DirectRequestKind.CANCEL && !outcome.retryable -> {
                                finish("Reset not confirmed · TEST retained")
                                return@post
                            }
                            !outcome.retryable -> store.recordDefiniteRejection(request.requestId)
                            else -> {
                                store.recordRetryableAttempt(request.requestId)
                                val currentConfig = configStore.snapshot()?.config
                                val fallback = currentConfig?.let { store.nextRequest(it, now()) }
                                if (
                                    request.kind == DirectRequestKind.TRIGGER &&
                                    request.provider == DirectProvider.GRAFANA &&
                                    fallback?.kind == DirectRequestKind.TRIGGER &&
                                    fallback.provider == DirectProvider.PUSHOVER
                                ) {
                                    updateNotification("Grafana pending · trying Pushover fallback")
                                    work()
                                } else {
                                    updateNotification("Provider pending · retry scheduled")
                                    schedule(RETRY_SECONDS)
                                }
                                return@post
                            }
                        }
                    } catch (_: Exception) {
                        updateNotification("Stored update retained · retry scheduled")
                        schedule(RETRY_SECONDS)
                        return@post
                    }
                    work()
                }
            }
        } catch (_: RejectedExecutionException) {
            DirectNetworkGate.sending.set(false)
        }
    }

    @SuppressLint("MissingPermission")
    private fun captureCached(config: DirectConfig, incidentId: String) {
        captureInProgress = true
        updateNotification("Checking last known GPS location")
        fused.lastLocation.addOnCompleteListener { task ->
            handler.post {
                captureInProgress = false
                if (stopped || store.snapshot()?.incidentId != incidentId) return@post
                val location = if (task.isSuccessful) task.result else null
                val queued = location
                    ?.let { queueFix(config, incidentId, it, DirectLocationSource.LAST_KNOWN_FUSED) }
                    ?: false
                if (queued) work() else captureFresh(
                    config,
                    incidentId,
                    requireNotNull(store.snapshot()?.acceptedAt),
                    TrackingSchedule(this, incidentId),
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun captureFresh(
        config: DirectConfig,
        incidentId: String,
        acceptedAt: Long,
        schedule: TrackingSchedule,
    ) {
        captureInProgress = true
        updateNotification("Requesting high-accuracy GPS fix")
        val token = CancellationTokenSource().also { cancellation = it }
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setMaxUpdateAgeMillis(0)
            .setDurationMillis(FRESH_FIX_TIMEOUT_MILLIS)
            .build()
        fused.getCurrentLocation(request, token.token).addOnCompleteListener { task ->
            handler.post {
                cancellation = null
                captureInProgress = false
                if (stopped || task.isCanceled || store.snapshot()?.incidentId != incidentId) return@post
                val currentNow = now()
                val delay = directTrackingCadenceSeconds(acceptedAt, currentNow, isLowBattery())
                schedule.nextCaptureAt = currentNow + delay
                val location = if (task.isSuccessful) task.result else null
                val queued = location
                    ?.let { queueFix(config, incidentId, it, DirectLocationSource.CURRENT_FUSED) }
                    ?: false
                if (queued) work() else ensureLocationUpdates(incidentId, acceptedAt)
            }
        }
    }

    private fun queueFix(
        config: DirectConfig,
        incidentId: String,
        location: Location,
        source: DirectLocationSource,
    ): Boolean {
        val sentAt = now()
        val capturedAt = location.time / 1_000
        if (!isUsableDirectLocation(
                location.latitude,
                location.longitude,
                capturedAt,
                sentAt,
                location.isMockLocation(),
            )
        ) return false
        val fix = DirectLocationFix(
            latitude = location.latitude,
            longitude = location.longitude,
            capturedAt = capturedAt,
            accuracyMeters = location.accuracy.takeIf { location.hasAccuracy() && it.isFinite() && it >= 0f },
            source = source,
        )
        return runCatching {
            if (store.snapshot()?.incidentId != incidentId) return@runCatching false
            store.queueLocation(config, fix, sentAt)
            DirectRecoveryWork.ensure(this)
            true
        }.getOrDefault(false)
    }

    @SuppressLint("MissingPermission")
    private fun ensureLocationUpdates(incidentId: String, acceptedAt: Long) {
        val intervalMillis = directTrackingCadenceSeconds(acceptedAt, now(), isLowBattery()) * 1_000
        if (locationUpdatesIncident == incidentId && locationUpdatesIntervalMillis == intervalMillis) {
            updateNotification("Provider accepted · GPS updates active")
            return
        }
        stopLocationUpdates()
        releaseRetryWakeLock()
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMillis)
            .setMinUpdateIntervalMillis(intervalMillis)
            .setMaxUpdateAgeMillis(0)
            .setWaitForAccurateLocation(true)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                handlePeriodicLocation(incidentId, result.lastLocation)
            }
        }
        locationUpdatesIncident = incidentId
        locationUpdatesIntervalMillis = intervalMillis
        locationCallback = callback
        fused.requestLocationUpdates(request, callback, Looper.getMainLooper())
            .addOnFailureListener {
                handler.post {
                    if (locationUpdatesIncident != incidentId || locationCallback !== callback || stopped) {
                        return@post
                    }
                    locationUpdatesIncident = null
                    locationUpdatesIntervalMillis = 0
                    locationCallback = null
                    updateNotification("GPS unavailable · retry scheduled")
                    schedule(RETRY_SECONDS)
                }
            }
        updateNotification("Provider accepted · GPS updates active")
    }

    private fun handlePeriodicLocation(expectedIncidentId: String, location: Location?) {
        val incidentId = locationUpdatesIncident?.takeIf { it == expectedIncidentId } ?: return
        if (stopped) return
        val state = store.snapshot()
        if (state?.incidentId != incidentId || state.acceptedAt == null) {
            stopLocationUpdates()
            return
        }
        val currentNow = now()
        if (currentNow >= requireNotNull(state.trackingExpiresAt)) {
            work()
            return
        }
        val config = configStore.snapshot()?.config ?: return finish("Configuration unavailable")
        if (!store.hasAcceptedLocationTarget(config)) {
            stopLocationUpdates()
            work()
            return
        }
        val schedule = TrackingSchedule(this, incidentId)
        if (currentNow < schedule.nextCaptureAt) return
        val delay = directTrackingCadenceSeconds(state.acceptedAt, currentNow, isLowBattery())
        schedule.nextCaptureAt = currentNow + delay
        val queued = location
            ?.let { queueFix(config, incidentId, it, DirectLocationSource.CURRENT_FUSED) }
            ?: false
        if (queued) {
            work()
        } else {
            updateNotification("No valid GPS fix · listening for next update")
            ensureLocationUpdates(incidentId, state.acceptedAt)
        }
    }

    private fun stopLocationUpdates() {
        val callback = locationCallback
        locationCallback = null
        locationUpdatesIncident = null
        locationUpdatesIntervalMillis = 0
        if (callback != null) fused.removeLocationUpdates(callback)
    }

    private fun schedule(seconds: Long) {
        handler.removeCallbacks(work)
        handler.postDelayed(work, seconds.coerceAtLeast(1) * 1_000)
    }

    private fun scheduleExpiryCheck(seconds: Long) {
        handler.removeCallbacks(expiryCheck)
        handler.postDelayed(expiryCheck, seconds.coerceAtLeast(1) * 1_000)
    }

    private fun finish(label: String) {
        stopped = true
        handler.removeCallbacksAndMessages(null)
        cancellation?.cancel()
        stopLocationUpdates()
        releaseRetryWakeLock()
        updateNotification(label)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun promoteForeground(forLocation: Boolean) {
        val type = if (forLocation) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        val text = if (forLocation) {
            "Provider accepted · preparing GPS"
        } else {
            "Stored TEST · contacting provider"
        }
        startForeground(NOTIFICATION_ID, notification(text), type)
    }

    private fun ensureChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "OpenDistress active TEST",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Visible while a TEST is contacting providers or requesting location updates"
                setSound(null, null)
                enableVibration(false)
            },
        )
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_status)
            .setContentTitle("OpenDistress TEST active")
            .setContentText(text)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        OngoingActivity.Builder(this, NOTIFICATION_ID, builder)
            .setStaticIcon(R.drawable.ic_status)
            .setTouchIntent(open)
            .setTitle("OpenDistress TEST")
            .setContentDescription("OpenDistress TEST is active. $text")
            .setStatus(
                Status.Builder()
                    .addTemplate("#status#")
                    .addPart("status", Status.TextPart(text))
                    .build(),
            )
            .build()
            .apply(this)
        return builder.build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun isLowBattery(): Boolean {
        val level = getSystemService(BatteryManager::class.java)
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            ?: -1
        return level in 0..20
    }

    private fun holdRetryWakeLock(until: Long, currentNow: Long) {
        if (until <= currentNow || retryWakeLock?.isHeld == true) return
        val timeoutMillis = ((until - currentNow) * 1_000).coerceAtMost(MAX_RETRY_WAKE_MILLIS)
        retryWakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:direct-provider-retry")
            .apply {
                setReferenceCounted(false)
                acquire(timeoutMillis)
            }
    }

    private fun releaseRetryWakeLock() {
        retryWakeLock?.takeIf(PowerManager.WakeLock::isHeld)?.release()
        retryWakeLock = null
    }

    private fun vibrateAccepted() {
        if (EncryptedDirectConfigStore.get(this).snapshot()?.config?.hapticFeedback == false) return
        getSystemService(Vibrator::class.java)?.takeIf(Vibrator::hasVibrator)?.vibrate(
            VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK),
        )
    }

    private fun now(): Long = System.currentTimeMillis() / 1_000

    companion object {
        private const val ACTION_START = "dev.opendistress.wear.direct.START_TRACKING"
        private const val ACTION_STOP = "dev.opendistress.wear.direct.STOP_TRACKING"
        private const val CHANNEL_ID = "opendistress-direct-test"
        private const val NOTIFICATION_ID = 8_201
        private const val RETRY_SECONDS = 30L
        private const val ROUTE_RECHECK_SECONDS = 5 * 60L
        private const val FRESH_FIX_TIMEOUT_MILLIS = 20_000L
        private const val MAX_RETRY_WAKE_MILLIS = 16 * 60 * 1_000L

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, DirectTrackingService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, DirectTrackingService::class.java).setAction(ACTION_STOP),
            )
        }

    }
}

@Suppress("DEPRECATION")
private fun Location.isMockLocation(): Boolean =
    if (Build.VERSION.SDK_INT >= 31) isMock else isFromMockProvider

private class TrackingSchedule(context: Context, incidentId: String) {
    private val preferences = context.getSharedPreferences("direct-tracking-schedule", Context.MODE_PRIVATE)
    private val currentIncident = preferences.getString(KEY_INCIDENT, null)

    init {
        if (currentIncident != incidentId) {
            preferences.edit().clear().putString(KEY_INCIDENT, incidentId).apply()
        }
    }

    var cachedAttempted: Boolean
        get() = preferences.getBoolean(KEY_CACHED_ATTEMPTED, false)
        set(value) {
            preferences.edit().putBoolean(KEY_CACHED_ATTEMPTED, value).apply()
        }

    var nextCaptureAt: Long
        get() = preferences.getLong(KEY_NEXT_CAPTURE_AT, 0L)
        set(value) {
            preferences.edit().putLong(KEY_NEXT_CAPTURE_AT, value).apply()
        }

    private companion object {
        const val KEY_INCIDENT = "incident_id"
        const val KEY_CACHED_ATTEMPTED = "cached_attempted"
        const val KEY_NEXT_CAPTURE_AT = "next_capture_at"
    }
}
