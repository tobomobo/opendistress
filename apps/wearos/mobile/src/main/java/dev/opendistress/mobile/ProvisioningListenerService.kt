// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.WearableListenerService

class ProvisioningListenerService : WearableListenerService() {
    override fun onDataChanged(dataEvents: DataEventBuffer) {
        ProvisioningCoordinator(this).handleEventsBlocking(dataEvents)
    }
}
