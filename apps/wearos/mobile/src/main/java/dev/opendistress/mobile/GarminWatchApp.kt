// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

/**
 * The OpenDistress app Garmin Connect reports on the connected watch. The
 * version is Garmin's own integer; the phone cannot see the Store's latest
 * version, so it only detects that the installed version changed.
 */
internal data class GarminWatchApp(
    val deviceName: String,
    val applicationId: String,
    val displayName: String,
    val version: Int,
) {
    fun summary(): String = "Watch app: ${displayName.ifBlank { "OpenDistress" }} · version $version"

    companion object {
        /** True only for a real change from a previously recorded version. */
        fun changed(previousVersion: Int?, currentVersion: Int): Boolean =
            previousVersion != null && previousVersion > 0 && currentVersion > 0 && previousVersion != currentVersion
    }
}
