// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

/** Owner-recorded physical drill evidence. Never provider or recipient telemetry. */
internal data class DrillEvidence(
    val revision: Long,
    val watch: String,
    val provider: String,
    val recordedAt: Long,
) {
    init {
        require(revision > 0 && recordedAt > 0)
        require(watch in setOf("Garmin", "Wear OS"))
        require(provider in setOf("Grafana", "Pushover"))
    }

    fun isCurrent(currentRevision: Long, now: Long): Boolean =
        revision == currentRevision && now >= recordedAt && now - recordedAt < 30L * 86_400
}
