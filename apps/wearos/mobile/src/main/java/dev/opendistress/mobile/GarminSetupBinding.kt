// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import java.security.MessageDigest

/** Session-only evidence for one physical watch, build and saved setup. */
internal data class GarminSetupBinding(
    val deviceId: Long,
    val appId: String,
    val revision: Long,
    val digest: String,
) {
    fun matches(deviceId: Long, appId: String, revision: Long, digest: String): Boolean =
        this.deviceId == deviceId && this.appId == appId && this.revision == revision &&
            MessageDigest.isEqual(this.digest.toByteArray(), digest.toByteArray())
}
