// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import java.security.MessageDigest

/** Session-only evidence for one physical watch, build and saved setup. */
internal data class GarminSetupBinding(
    val deviceId: Long,
    val appId: String,
    val revision: Long,
    val digest: String,
    val startedAt: Long = 0,
) {
    // Second-resolution watch timestamps cannot prove freshness within the
    // starting second or with clock skew. Keep those cases unconfirmed.
    fun isFreshAck(storedAt: Long, now: Long): Boolean =
        storedAt > startedAt && storedAt <= now

    fun matches(deviceId: Long, appId: String, revision: Long, digest: String): Boolean =
        this.deviceId == deviceId && this.appId == appId && this.revision == revision &&
            MessageDigest.isEqual(this.digest.toByteArray(), digest.toByteArray())
}
