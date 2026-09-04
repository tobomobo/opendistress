// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import android.content.Context

/** Explicit setup destination; never infer authorization from nearby devices. */
internal enum class WatchTarget { GARMIN, PIXEL }

internal class WatchTargetStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("watch-target", Context.MODE_PRIVATE)
    fun selected(): WatchTarget? = preferences.getString("selected", null)?.let {
        runCatching { WatchTarget.valueOf(it) }.getOrNull()
    }
    fun select(target: WatchTarget) {
        preferences.edit().putString("selected", target.name).apply()
    }
}
