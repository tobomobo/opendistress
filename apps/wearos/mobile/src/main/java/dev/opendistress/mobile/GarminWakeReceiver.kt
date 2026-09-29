// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Starts the process when Garmin Connect delivers a watch message while this
 * app is not running. It only initializes the SDK link, which then registers
 * its own receiver; the watch repeats its bounded location request, so the
 * repeat is parsed and validated there. No payload is trusted or read here.
 */
class GarminWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_INCOMING_MESSAGE) return
        if (WatchTargetStore(context).selected() != WatchTarget.GARMIN) return
        GarminCompanionLink.get(context).initialize()
    }

    companion object {
        const val ACTION_INCOMING_MESSAGE = "com.garmin.android.connectiq.INCOMING_MESSAGE"
    }
}
