// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import android.content.Context
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataItem
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import dev.opendistress.shared.DirectConfig
import dev.opendistress.shared.DirectConfigAck
import dev.opendistress.shared.ProvisioningCrypto
import dev.opendistress.shared.ProvisioningDataKeys
import dev.opendistress.shared.ProvisioningPaths
import dev.opendistress.shared.WatchKeyAnnouncement
import dev.opendistress.shared.WatchKeyRequest
import java.security.SecureRandom

internal class ProvisioningCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val store = SecureProvisioningStore.get(appContext)
    private val dataClient = Wearable.getDataClient(appContext)

    fun snapshot(): ProvisioningState = store.snapshot()

    fun save(config: DirectConfig, callback: (String) -> Unit) {
        store.replace(store.snapshot().afterSave(config))
        synchronize(callback)
    }

    fun synchronize(callback: (String) -> Unit = {}, force: Boolean = false) {
        val state = store.snapshot()
        val config = state.config
        if (config == null) {
            callback("No configuration saved")
            return
        }
        if (!force && state.pending == null &&
            state.confirmed?.revision == config.revision &&
            state.confirmed.configDigestSha256.contentEquals(config.digestSha256())
        ) {
            requestWatchKey { callback(statusDescription(store.snapshot())) }
            return
        }
        requestWatchKey { scanAndPublish(config, callback) }
    }

    private fun requestWatchKey(afterRequest: () -> Unit) {
        val requestPayload = WatchKeyRequest(
            nonce = ByteArray(16).also(SecureRandom()::nextBytes),
            createdAtEpochMillis = System.currentTimeMillis().coerceAtLeast(1),
        ).canonicalBytes()
        val request = PutDataMapRequest.create(ProvisioningPaths.KEY_REQUEST).run {
            dataMap.putByteArray(ProvisioningDataKeys.PAYLOAD, requestPayload)
            asPutDataRequest().setUrgent()
        }
        dataClient.putDataItem(request).addOnCompleteListener {
            afterRequest()
        }
    }

    private fun scanAndPublish(config: DirectConfig, callback: (String) -> Unit) {
        dataClient.dataItems
            .addOnSuccessListener { items ->
                val announcements = try {
                    items.mapNotNull(::announcementFrom)
                } finally {
                    items.release()
                }
                val target = announcements
                    .distinctBy { it.watchId }
                    .sortedWith(compareByDescending<WatchKeyAnnouncement> { it.keyVersion }.thenBy { it.watchId })
                    .firstOrNull()
                if (target == null) {
                    callback("Saved securely on phone — waiting for a watch key")
                } else {
                    publish(config, target, callback)
                }
            }
            .addOnFailureListener {
                callback("Saved securely on phone — watch connection unavailable")
            }
    }

    fun handleEvents(events: DataEventBuffer, callback: (String) -> Unit = {}) {
        var keySeen = false
        events.forEach { event ->
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            when {
                event.dataItem.uri.path?.startsWith(ProvisioningPaths.WATCH_KEY_PREFIX) == true -> {
                    keySeen = announcementFrom(event.dataItem) != null || keySeen
                }
                event.dataItem.uri.path?.startsWith(ProvisioningPaths.ACK_PREFIX) == true -> {
                    handleAck(event.dataItem)
                }
            }
        }
        callback(statusDescription(store.snapshot()))
        if (keySeen) synchronize(callback, force = true)
    }

    fun statusDescription(state: ProvisioningState = store.snapshot()): String = when {
        state.config == null -> "Not configured — no alert route is stored"
        state.pending != null ->
            state.pending.watchId?.let {
                "Sent to watch ${shortWatchId(it)} — waiting for confirmation"
            } ?: "Saved securely on phone — waiting for watch"
        state.confirmed?.revision == state.config.revision ->
            "Confirmed on watch ${shortWatchId(state.confirmed.watchId)} — TEST route ready"
        else -> "Saved securely on phone — watch confirmation unavailable"
    }

    private fun publish(
        config: DirectConfig,
        announcement: WatchKeyAnnouncement,
        callback: (String) -> Unit,
    ) {
        val envelope = try {
            ProvisioningCrypto.seal(config, announcement)
        } catch (_: Exception) {
            callback("Watch key was invalid — configuration was not sent")
            return
        }
        val request = PutDataMapRequest.create(ProvisioningPaths.config(announcement.watchId)).run {
            dataMap.putByteArray(ProvisioningDataKeys.PAYLOAD, envelope.canonicalBytes())
            dataMap.putLong(ProvisioningDataKeys.REVISION, config.revision)
            dataMap.putByteArray(ProvisioningDataKeys.DIGEST_SHA256, config.digestSha256())
            asPutDataRequest().setUrgent()
        }
        dataClient.putDataItem(request)
            .addOnSuccessListener {
                val current = store.snapshot()
                if (current.config?.revision == config.revision &&
                    current.config.digestSha256().contentEquals(config.digestSha256())
                ) {
                    store.replace(current.afterPublish(announcement.watchId))
                    callback(statusDescription(store.snapshot()))
                } else {
                    synchronize(callback)
                }
            }
            .addOnFailureListener {
                callback("Saved securely on phone — sending to watch failed")
            }
    }

    private fun announcementFrom(item: DataItem): WatchKeyAnnouncement? {
        val pathWatchId = ProvisioningPaths.watchIdFrom(
            item.uri.path ?: return null,
            ProvisioningPaths.WATCH_KEY_PREFIX,
        ) ?: return null
        return runCatching {
            val payload = DataMapItem.fromDataItem(item).dataMap
                .getByteArray(ProvisioningDataKeys.PAYLOAD) ?: return null
            WatchKeyAnnouncement.fromCanonicalBytes(payload).takeIf { it.watchId == pathWatchId }
        }.getOrNull()
    }

    private fun handleAck(item: DataItem) {
        val pathWatchId = ProvisioningPaths.watchIdFrom(
            item.uri.path ?: return,
            ProvisioningPaths.ACK_PREFIX,
        ) ?: return
        val ack = runCatching {
            val dataMap = DataMapItem.fromDataItem(item).dataMap
            val payload = dataMap.getByteArray(ProvisioningDataKeys.PAYLOAD) ?: return
            DirectConfigAck.fromCanonicalBytes(payload)
        }.getOrNull() ?: return
        if (ack.watchId != pathWatchId) return
        val before = store.snapshot()
        val after = before.afterAck(ack)
        if (after != before) store.replace(after)
    }

    private fun shortWatchId(value: String?): String =
        value?.take(8) ?: "unknown"
}

internal fun nextRevision(previous: Long?, nowMillis: Long): Long {
    val afterPrevious = previous?.let { Math.addExact(it, 1) } ?: 1L
    return maxOf(afterPrevious, nowMillis.coerceAtLeast(1))
}
