// SPDX-License-Identifier: MIT
package dev.opendistress.wear.direct

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
    private val store by lazy { EncryptedDirectConfigStore(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        publishIdentity()
        store.snapshot()?.ack?.let(::publishAck)
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path == PATH_IDENTITY_REQUEST) publishIdentity()
    }

    override fun onDataChanged(dataEvents: DataEventBuffer) {
        dataEvents.forEach { event ->
            val path = event.dataItem.uri.path.orEmpty()
            if (event.type == DataEvent.TYPE_CHANGED && path == ProvisioningPaths.KEY_REQUEST) {
                publishIdentity()
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
                publishAck(store.install(envelope))
            } catch (_: Exception) {
                // Deliberately publish no ACK for malformed, unauthentic, or stale input.
            } finally {
                envelope.fill(0)
            }
        }
    }

    private fun publishIdentity() {
        val announcement = store.watchAnnouncement()
        val request = PutDataMapRequest.create(ProvisioningPaths.watchKey(announcement.watchId)).apply {
            dataMap.putByteArray(ProvisioningDataKeys.PAYLOAD, announcement.canonicalBytes())
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(this).putDataItem(request)
    }

    private fun publishAck(ack: DirectConfigAck) {
        val request = PutDataMapRequest.create(ProvisioningPaths.ack(ack.watchId)).apply {
            dataMap.putByteArray(ProvisioningDataKeys.PAYLOAD, ack.canonicalBytes())
            dataMap.putLong(ProvisioningDataKeys.REVISION, ack.revision)
            dataMap.putByteArray(ProvisioningDataKeys.DIGEST_SHA256, ack.configDigestSha256)
        }.asPutDataRequest().setUrgent()
        Wearable.getDataClient(this).putDataItem(request)
    }

    companion object {
        const val PATH_IDENTITY_REQUEST = "/opendistress/direct-config/identity-request"
    }
}
