// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import android.app.Application

class OpenDistressCompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        if (WatchTargetStore(this).selected() == WatchTarget.GARMIN) {
            GarminCompanionLink.get(this).initialize()
        }
    }
}
