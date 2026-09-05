// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Build
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import dev.opendistress.shared.DirectConfig
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Garmin Mobile SDK adapter. The watch remains the alert authority; this link
 * can only provision TEST settings and return a post-acceptance location candidate.
 */
internal class GarminCompanionLink private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val connectIQ = ConnectIQ.getInstance(appContext, ConnectIQ.IQConnectType.WIRELESS)
    private val preferences = appContext.getSharedPreferences("garmin-link-v1", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArraySet<(GarminLinkStatus) -> Unit>()
    private val locationClient = LocationServices.getFusedLocationProviderClient(appContext)
    private var initialized = false
    private var ready = false
    var connectedWatchName: String? = null
        private set
    private var observedDeviceId: Long? = null
    private var currentStatus: GarminLinkStatus =
        GarminLinkStatus.Unavailable("Starting Garmin connection…")
    private var pendingConfig: DirectConfig? = null
    private var pendingTransfer: GarminSetupBinding? = null
    private var confirmedTransfer: GarminSetupBinding? = null
    private var confirmedAt: Long? = null

    private val applicationEvents = ConnectIQ.IQApplicationEventListener { device, installedApp, messages, status ->
        if (installedApp.applicationId !in GarminCompanionProtocol.GARMIN_APP_IDS) {
            return@IQApplicationEventListener
        }
        if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
            update(GarminLinkStatus.Attention("Garmin message transfer failed"))
            return@IQApplicationEventListener
        }
        messages.forEach { message ->
            GarminCompanionProtocol.parseAck(message)?.let { acceptAck(device, installedApp, it) }
            GarminCompanionProtocol.parseAcceptedIncident(message)?.let {
                requestPhoneLocation(device, installedApp, it)
            }
        }
    }

    @Synchronized
    fun initialize() {
        if (WatchTargetStore(appContext).selected() != WatchTarget.GARMIN) return
        if (initialized) return
        initialized = true
        connectIQ.initialize(appContext, false, object : ConnectIQ.ConnectIQListener {
            override fun onSdkReady() {
                ready = true
                val saved = pendingConfig ?: savedConfig()
                if (preferences.getBoolean(KEY_GARMIN_ENABLED, false) && saved != null) {
                    sync(saved)
                } else {
                    refresh()
                }
            }

            override fun onInitializeError(error: ConnectIQ.IQSdkErrorStatus) {
                ready = false
                initialized = false
                update(
                    GarminLinkStatus.Unavailable(
                        when (error) {
                            ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED ->
                                "Install Garmin Connect to link a Garmin watch"
                            ConnectIQ.IQSdkErrorStatus.GCM_UPGRADE_NEEDED ->
                                "Update Garmin Connect to link a Garmin watch"
                            else -> "Garmin Connect service is unavailable"
                        },
                    ),
                )
            }

            override fun onSdkShutDown() {
                ready = false
                initialized = false
                observedDeviceId = null
                pendingTransfer = null
                confirmedTransfer = null
                update(GarminLinkStatus.Unavailable("Garmin connection stopped"))
            }
        })
    }

    fun observe(listener: (GarminLinkStatus) -> Unit) {
        listeners += listener
        listener(currentStatus)
    }

    fun removeObserver(listener: (GarminLinkStatus) -> Unit) {
        listeners -= listener
    }

    fun refresh() {
        if (WatchTargetStore(appContext).selected() != WatchTarget.GARMIN) return
        if (!ready) return
        val devices = connectedDevices()
        when (devices.size) {
            0 -> update(GarminLinkStatus.Waiting("Garmin Connect is ready — connect your watch"))
            1 -> checkApplication(devices.single(), null)
            else -> update(GarminLinkStatus.Attention("Multiple Garmin watches are connected — keep only the intended watch connected"))
        }
    }

    fun resume() {
        if (!initialized) {
            initialize()
            return
        }
        val saved = savedConfig()
        if (preferences.getBoolean(KEY_GARMIN_ENABLED, false) && saved != null) {
            sync(saved)
        } else {
            refresh()
        }
    }

    fun sync(config: DirectConfig) {
        if (WatchTargetStore(appContext).selected() != WatchTarget.GARMIN) return
        // Re-send on each explicit sync/resume: the same physical watch may
        // have had its app reinstalled since its last acknowledgement.
        confirmedTransfer = null
        pendingTransfer = null
        preferences.edit().putBoolean(KEY_GARMIN_ENABLED, true).apply()
        pendingConfig = config
        if (!ready) {
            update(GarminLinkStatus.Waiting("Saved securely — waiting for Garmin Connect"))
            initialize()
            return
        }
        val devices = connectedDevices()
        when (devices.size) {
            0 -> update(GarminLinkStatus.Waiting("Saved securely — connect the Garmin watch to send setup"))
            1 -> checkApplication(devices.single(), config)
            else -> update(GarminLinkStatus.Attention("Setup was not sent: multiple Garmin watches are connected"))
        }
    }

    fun locationAssistEnabled(): Boolean = preferences.getBoolean(KEY_LOCATION_ASSIST, false)

    fun setLocationAssistEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_LOCATION_ASSIST, enabled).apply()
    }

    private fun connectedDevices(): List<IQDevice> = runCatching {
        connectIQ.knownDevices.filter { connectIQ.getDeviceStatus(it) == IQDevice.IQDeviceStatus.CONNECTED }
            .also { connectedWatchName = it.singleOrNull()?.friendlyName }
    }.getOrElse {
        update(GarminLinkStatus.Unavailable("Garmin device list is unavailable"))
        emptyList()
    }

    private fun checkApplication(device: IQDevice, config: DirectConfig?) {
        if (observedDeviceId != device.deviceIdentifier) {
            observedDeviceId = device.deviceIdentifier
            runCatching {
                connectIQ.registerForDeviceEvents(device) { _, _ -> refresh() }
            }.onFailure { observedDeviceId = null }
        }
        findInstalledApplication(device, 0, config)
    }

    private fun findInstalledApplication(device: IQDevice, index: Int, config: DirectConfig?) {
        if (index >= GarminCompanionProtocol.GARMIN_APP_IDS.size) {
            update(GarminLinkStatus.Attention("Install OpenDistress on ${device.friendlyName} before syncing"))
            return
        }
        val applicationId = GarminCompanionProtocol.GARMIN_APP_IDS[index]
        runCatching {
            connectIQ.getApplicationInfo(
                applicationId,
                device,
                object : ConnectIQ.IQApplicationInfoListener {
                    override fun onApplicationInfoReceived(installed: IQApp) {
                        // Non-binder mode requires a device/app registration.
                        // The listener-only overload registers the binder path.
                        runCatching {
                            connectIQ.registerForAppEvents(device, installed, applicationEvents)
                        }.onFailure {
                            update(GarminLinkStatus.Attention("Garmin app message registration failed"))
                            return
                        }
                        if (config == null) {
                            update(readiness(device, installed))
                        } else {
                            sendConfiguration(device, installed, config)
                        }
                    }

                    override fun onApplicationNotInstalled(applicationId: String) {
                        findInstalledApplication(device, index + 1, config)
                    }
                },
            )
        }.onFailure {
            update(GarminLinkStatus.Unavailable("Could not inspect the OpenDistress Garmin app"))
        }
    }

    private fun sendConfiguration(device: IQDevice, installed: IQApp, config: DirectConfig) {
        if (WatchTargetStore(appContext).selected() != WatchTarget.GARMIN) return
        val payload = GarminCompanionProtocol.configMessage(config)
        val transfer = GarminSetupBinding(device.deviceIdentifier, installed.applicationId,
            config.revision, GarminCompanionProtocol.digest(config), System.currentTimeMillis() / 1_000)
        pendingTransfer = transfer
        update(GarminLinkStatus.Waiting("Sending setup to ${device.friendlyName}…"))
        runCatching {
            connectIQ.sendMessage(device, installed, payload) { _, _, status ->
                // ACK may arrive before the send callback; do not downgrade it.
                if (pendingTransfer != transfer) return@sendMessage
                if (status == ConnectIQ.IQMessageStatus.SUCCESS) {
                    update(
                        GarminLinkStatus.Waiting(
                            "Sent to ${device.friendlyName} — open OpenDistress there and check READY TEST",
                        ),
                    )
                } else {
                    update(GarminLinkStatus.Attention("Garmin transfer failed: ${status.name.lowercase()}"))
                }
            }
        }.onFailure {
            update(GarminLinkStatus.Attention("Garmin setup could not be sent"))
        }
    }

    private fun acceptAck(device: IQDevice, installedApp: IQApp, ack: GarminConfigAck) {
        val transfer = pendingTransfer ?: return
        val saved = savedConfig() ?: return
        if (!transfer.isFreshAck(ack.storedAtEpochSeconds, System.currentTimeMillis() / 1_000)) return
        if (!transfer.matches(device.deviceIdentifier, installedApp.applicationId,
                ack.revision, ack.configDigest) ||
            !transfer.matches(device.deviceIdentifier, installedApp.applicationId,
                saved.revision, GarminCompanionProtocol.digest(saved))) return
        confirmedTransfer = transfer
        confirmedAt = ack.storedAtEpochSeconds
        pendingTransfer = null
        pendingConfig = null
        update(GarminLinkStatus.Ready("Saved setup confirmed on ${device.friendlyName}", confirmedAt))
    }

    private fun readiness(device: IQDevice, installedApp: IQApp? = null): GarminLinkStatus {
        val config = savedConfig()
            ?: return GarminLinkStatus.Waiting("${device.friendlyName} connected — save setup to provision it")
        return if (installedApp != null && confirmedTransfer?.matches(
                device.deviceIdentifier, installedApp.applicationId,
                config.revision, GarminCompanionProtocol.digest(config)) == true) {
            GarminLinkStatus.Ready("Saved setup confirmed on ${device.friendlyName}", confirmedAt)
        } else {
            GarminLinkStatus.Waiting("${device.friendlyName} connected — setup is not yet confirmed")
        }
    }

    private fun requestPhoneLocation(device: IQDevice, installedApp: IQApp, incident: GarminAcceptedIncident) {
        val config = savedConfig() ?: return
        if (!MessageDigest.isEqual(
                incident.configDigest.toByteArray(),
                GarminCompanionProtocol.digest(config).toByteArray(),
            ) || !locationAssistEnabled()
        ) return
        if (appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            update(GarminLinkStatus.Attention("Alert accepted, but phone location permission is missing"))
            return
        }
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
            .setDurationMillis(20_000)
            .setMaxUpdateAgeMillis(0)
            .build()
        val cancellation = CancellationTokenSource()
        locationClient.getCurrentLocation(request, cancellation.token)
            .addOnSuccessListener { location ->
                if (location == null || isMock(location)) {
                    update(GarminLinkStatus.Attention("Alert accepted; phone could not obtain a real location fix"))
                    return@addOnSuccessListener
                }
                sendLocationCandidate(device, installedApp, incident, location)
            }
            .addOnFailureListener {
                update(GarminLinkStatus.Attention("Alert accepted; phone location request failed"))
            }
    }

    private fun sendLocationCandidate(
        device: IQDevice,
        installedApp: IQApp,
        incident: GarminAcceptedIncident,
        location: Location,
    ) {
        val capturedAt = location.time / 1_000
        val now = System.currentTimeMillis() / 1_000
        if (capturedAt < incident.acceptedAtEpochSeconds || capturedAt > now + 5 || now >= incident.expiresAtEpochSeconds ||
            !location.hasAccuracy() || !location.accuracy.isFinite() || location.accuracy > 10_000f
        ) {
            update(GarminLinkStatus.Attention("Phone location was rejected because its age or accuracy was unsafe"))
            return
        }
        val payload = runCatching {
            GarminCompanionProtocol.locationCandidate(
                incident,
                capturedAt,
                location.latitude,
                location.longitude,
                location.accuracy,
            )
        }.getOrNull() ?: return
        runCatching {
            connectIQ.sendMessage(device, installedApp, payload) { _, _, status ->
                update(
                    if (status == ConnectIQ.IQMessageStatus.SUCCESS) {
                        // Transport completion is not a configuration ACK.
                        readiness(device, installedApp)
                    } else {
                        GarminLinkStatus.Attention("Phone location could not reach the Garmin watch")
                    },
                )
            }
        }.onFailure {
            update(GarminLinkStatus.Attention("Phone location could not reach the Garmin watch"))
        }
    }

    @Suppress("DEPRECATION")
    private fun isMock(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= 31) location.isMock else location.isFromMockProvider

    private fun update(status: GarminLinkStatus) {
        if (status is GarminLinkStatus.Unavailable) connectedWatchName = null
        currentStatus = status
        listeners.forEach { it(status) }
    }

    private fun savedConfig(): DirectConfig? = try {
        SecureProvisioningStore.get(appContext).snapshot().config
    } catch (_: Exception) {
        // Never let Keystore/state failure kill Application.onCreate before
        // MainActivity can show its existing storage recovery screen.
        update(GarminLinkStatus.Unavailable("Stored setup could not be read — open setup to recover"))
        null
    }

    companion object {
        private const val KEY_LOCATION_ASSIST = "location-assist"
        private const val KEY_GARMIN_ENABLED = "garmin-enabled"
        @Volatile private var instance: GarminCompanionLink? = null

        fun get(context: Context): GarminCompanionLink = instance ?: synchronized(this) {
            instance ?: GarminCompanionLink(context).also { instance = it }
        }
    }
}

internal sealed interface GarminLinkStatus {
    val description: String
    data class Ready(override val description: String, val confirmedAt: Long? = null) : GarminLinkStatus
    data class Waiting(override val description: String) : GarminLinkStatus
    data class Attention(override val description: String) : GarminLinkStatus
    data class Unavailable(override val description: String) : GarminLinkStatus
}
