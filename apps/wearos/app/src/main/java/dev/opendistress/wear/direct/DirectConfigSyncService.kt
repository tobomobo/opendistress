// SPDX-License-Identifier: MIT
package dev.opendistress.wear.direct

import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import dev.opendistress.shared.DirectConfigAck
import dev.opendistress.shared.ProvisioningDataKeys
import dev.opendistress.shared.ProvisioningPaths

/**
 * Receives only phone-prepared, watch-public-key-encrypted TEST configuration.
 * It never accepts or publishes LIVE v2 keys and never participates in alerts.
 */
class DirectConfigSyncService : WearableListenerService() {
    private val store by lazy { EncryptedDirectConfigStore.get(applicationContext) }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == PATH_IDENTITY_REQUEST) publishIdentityAndAckBlocking()
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            val path = event.dataItem.uri.path.orEmpty()
            if (event.type == DataEvent.TYPE_CHANGED && path == ProvisioningPaths.KEY_REQUEST) {
                publishIdentityAndAckBlocking()
                return@forEach
            }
            if (
                event.type != DataEvent.TYPE_CHANGED ||
                ProvisioningPaths.watchIdFrom(
                    path,
                    ProvisioningPaths.CONFIG_PREFIX,
                ) != store.watchAnnouncement().watchId
            ) return@forEach
            val envelope = DataMapItem.fromDataItem(event.dataItem)
                .dataMap
                .getByteArray(ProvisioningDataKeys.PAYLOAD)
                ?: return@forEach
            try {
                publishAckBlocking(store.install(envelope))
                if (runCatching { DirectTestStore.get(this).snapshot() }.getOrNull() != null) {
                    DirectRecoveryWork.ensure(this)
                }
            } catch (_: Exception) {
                // Deliberately publish no ACK for malformed, unauthentic, or stale input.
            } finally {
                envelope.fill(0)
            }
        }
    }

    /** WearableListenerService invokes message and data callbacks on its background handler thread. */
    private fun publishIdentityAndAckBlocking() {
        val currentStore = runCatching { store }.getOrNull() ?: return
        runCatching { publishIdentityBlocking(currentStore) }
        currentStore.snapshot()?.ack?.let { ack ->
            runCatching { publishAckBlocking(ack) }
        }
    }

    private fun publishIdentityBlocking(currentStore: EncryptedDirectConfigStore) {
        val announcement = currentStore.watchAnnouncement()
        val request = PutDataMapRequest.create(ProvisioningPaths.watchKey(announcement.watchId)).apply {
            dataMap.putByteArray(ProvisioningDataKeys.PAYLOAD, announcement.canonicalBytes())
        }.asPutDataRequest().setUrgent()
        Tasks.await(Wearable.getDataClient(this).putDataItem(request))
    }

    private fun publishAckBlocking(ack: DirectConfigAck) {
        val request = PutDataMapRequest.create(ProvisioningPaths.ack(ack.watchId)).apply {
            dataMap.putByteArray(ProvisioningDataKeys.PAYLOAD, ack.canonicalBytes())
            dataMap.putLong(ProvisioningDataKeys.REVISION, ack.revision)
            dataMap.putByteArray(ProvisioningDataKeys.DIGEST_SHA256, ack.configDigestSha256)
        }.asPutDataRequest().setUrgent()
        Tasks.await(Wearable.getDataClient(this).putDataItem(request))
    }

    companion object {
        const val PATH_IDENTITY_REQUEST = "/opendistress/direct-config/identity-request"
    }
}
