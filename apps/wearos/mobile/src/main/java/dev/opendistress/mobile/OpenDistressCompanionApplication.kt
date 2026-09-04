// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import android.app.Application

class OpenDistressCompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        GarminCompanionLink.get(this).initialize()
    }
}
