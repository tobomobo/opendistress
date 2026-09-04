// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import android.content.Context
import com.google.android.gms.tasks.Tasks
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
    private val nodeClient = Wearable.getNodeClient(appContext)

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
        dataClient.putDataItem(watchKeyRequest()).addOnCompleteListener {
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
                nodeClient.connectedNodes
                    .addOnSuccessListener { nodes ->
                        val connectedNodeIds = nodes.mapTo(mutableSetOf()) { it.id }
                        if (connectedNodeIds.size > 1) {
                            callback("Multiple connected watches found — disconnect all but the intended watch")
                        } else {
                            publishToOnlyConnectedWatch(
                                config,
                                connectedWatchAnnouncements(announcements, connectedNodeIds),
                                callback,
                            )
                        }
                    }
                    .addOnFailureListener {
                        callback("Saved securely on phone — watch connection unavailable")
                    }
            }
            .addOnFailureListener {
                callback("Saved securely on phone — watch connection unavailable")
            }
    }

    fun handleEvents(events: DataEventBuffer, callback: (String) -> Unit = {}) {
        val keySeen = applyEvents(events)
        callback(statusDescription(store.snapshot()))
        if (keySeen) synchronize(callback, force = true)
    }

    /** WearableListenerService invokes this on its background handler thread. */
    fun handleEventsBlocking(events: DataEventBuffer) {
        if (applyEvents(events)) synchronizeBlocking(force = true)
    }

    private fun applyEvents(events: DataEventBuffer): Boolean {
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
        return keySeen
    }

    fun statusDescription(state: ProvisioningState = store.snapshot()): String = when {
        state.config == null -> "Not configured — no alert route is stored"
        state.pending != null ->
            state.pending.watchId?.let {
                "Queued for watch ${shortWatchId(it)} — waiting for confirmation"
            } ?: "Saved securely on phone — waiting for watch"
        state.confirmed?.revision == state.config.revision ->
            "Confirmed on watch ${shortWatchId(state.confirmed.watchId)} — TEST route ready"
        else -> "Saved securely on phone — watch confirmation unavailable"
    }

    private fun publish(
        config: DirectConfig,
        target: SourcedWatchAnnouncement,
        callback: (String) -> Unit,
    ) {
        val announcement = target.announcement
        val envelope = try {
            ProvisioningCrypto.seal(config, announcement)
        } catch (_: Exception) {
            callback("Watch key was invalid — configuration was not sent")
            return
        }
        val request = configRequest(config, announcement, envelope.canonicalBytes())
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
                callback("Saved securely on phone — queueing for watch failed")
            }
    }

    private fun announcementFrom(item: DataItem): SourcedWatchAnnouncement? {
        val pathWatchId = ProvisioningPaths.watchIdFrom(
            item.uri.path ?: return null,
            ProvisioningPaths.WATCH_KEY_PREFIX,
        ) ?: return null
        val creatorNodeId = item.uri.host?.takeIf(String::isNotBlank) ?: return null
        return runCatching {
            val payload = DataMapItem.fromDataItem(item).dataMap
                .getByteArray(ProvisioningDataKeys.PAYLOAD) ?: return null
            WatchKeyAnnouncement.fromCanonicalBytes(payload)
                .takeIf { it.watchId == pathWatchId }
                ?.let { SourcedWatchAnnouncement(creatorNodeId, it) }
        }.getOrNull()
    }

    private fun publishToOnlyConnectedWatch(
        config: DirectConfig,
        connected: List<SourcedWatchAnnouncement>,
        callback: (String) -> Unit,
    ) {
        when (connected.size) {
            0 -> callback("Saved securely on phone — waiting for a connected watch key")
            1 -> publish(config, connected.single(), callback)
            else -> callback("Multiple connected watches found — disconnect all but the intended watch")
        }
    }

    private fun synchronizeBlocking(force: Boolean) {
        val state = store.snapshot()
        val config = state.config ?: return
        runCatching { Tasks.await(dataClient.putDataItem(watchKeyRequest())) }
        if (!force && state.pending == null &&
            state.confirmed?.revision == config.revision &&
            state.confirmed.configDigestSha256.contentEquals(config.digestSha256())
        ) return

        val announcements = try {
            val items = Tasks.await(dataClient.dataItems)
            try {
                items.mapNotNull(::announcementFrom)
            } finally {
                items.release()
            }
        } catch (_: Exception) {
            return
        }
        val connectedNodeIds = try {
            Tasks.await(nodeClient.connectedNodes).mapTo(mutableSetOf()) { it.id }
        } catch (_: Exception) {
            return
        }
        if (connectedNodeIds.size > 1) return
        val target = connectedWatchAnnouncements(announcements, connectedNodeIds).singleOrNull() ?: return
        publishBlocking(config, target)
    }

    private fun publishBlocking(config: DirectConfig, target: SourcedWatchAnnouncement) {
        val envelope = runCatching { ProvisioningCrypto.seal(config, target.announcement) }.getOrNull() ?: return
        val request = configRequest(config, target.announcement, envelope.canonicalBytes())
        try {
            Tasks.await(dataClient.putDataItem(request))
        } catch (_: Exception) {
            return
        }
        val current = store.snapshot()
        if (current.config?.revision == config.revision &&
            current.config.digestSha256().contentEquals(config.digestSha256())
        ) {
            store.replace(current.afterPublish(target.announcement.watchId))
        } else {
            synchronizeBlocking(force = false)
        }
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

    private fun watchKeyRequest() = PutDataMapRequest.create(ProvisioningPaths.KEY_REQUEST).run {
        dataMap.putByteArray(
            ProvisioningDataKeys.PAYLOAD,
            WatchKeyRequest(
                nonce = ByteArray(16).also(SecureRandom()::nextBytes),
                createdAtEpochMillis = System.currentTimeMillis().coerceAtLeast(1),
            ).canonicalBytes(),
        )
        asPutDataRequest().setUrgent()
    }

    private fun configRequest(
        config: DirectConfig,
        announcement: WatchKeyAnnouncement,
        envelope: ByteArray,
    ) = PutDataMapRequest.create(ProvisioningPaths.config(announcement.watchId)).run {
        dataMap.putByteArray(ProvisioningDataKeys.PAYLOAD, envelope)
        dataMap.putLong(ProvisioningDataKeys.REVISION, config.revision)
        dataMap.putByteArray(ProvisioningDataKeys.DIGEST_SHA256, config.digestSha256())
        asPutDataRequest().setUrgent()
    }
}

internal data class SourcedWatchAnnouncement(
    val creatorNodeId: String,
    val announcement: WatchKeyAnnouncement,
) {
    init {
        require(creatorNodeId.isNotBlank())
    }
}

internal fun connectedWatchAnnouncements(
    announcements: List<SourcedWatchAnnouncement>,
    connectedNodeIds: Set<String>,
): List<SourcedWatchAnnouncement> = announcements
    .filter { it.creatorNodeId in connectedNodeIds }
    .distinctBy { it.creatorNodeId to it.announcement.watchId }
    .sortedWith(compareBy({ it.creatorNodeId }, { it.announcement.watchId }))

internal fun nextRevision(previous: Long?, nowMillis: Long): Long {
    val afterPrevious = previous?.let { Math.addExact(it, 1) } ?: 1L
    return maxOf(afterPrevious, nowMillis.coerceAtLeast(1))
}
