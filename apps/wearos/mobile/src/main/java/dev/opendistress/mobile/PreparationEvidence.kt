// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import dev.opendistress.shared.DirectConfig

/** Historical owner observations, never a live health score or automatic telemetry. */
internal data class PreparationEvidence(
    val provider: String,
    val recordedAt: Long?,
    val status: String,
) {
    companion object {
        fun forSetup(config: DirectConfig?, watch: WatchTarget?, drills: List<DrillEvidence>, now: Long): List<PreparationEvidence> {
            if (config == null || watch == null) return emptyList()
            val name = if (watch == WatchTarget.GARMIN) "Garmin" else "Wear OS"
            val providers = buildList {
                if (config.grafanaWebhookUrl != null) add("Grafana")
                if (config.pushoverUserKey != null) add("Pushover")
            }
            return providers.map { provider ->
                val drill = drills.filter { it.watch == name && it.provider == provider }.maxByOrNull { it.recordedAt }
                PreparationEvidence(provider, drill?.recordedAt, when {
                    drill == null -> "Delivery and real GPS: no physical drill recorded"
                    drill.revision != config.revision -> "Saved setup changed — repeat delivery and GPS drill"
                    now < drill.recordedAt -> "Clock changed — drill time cannot be verified"
                    !drill.isCurrent(config.revision, now) -> "Drill older than 30 days — rehearse again"
                    else -> "Owner recorded delivery + real GPS for this saved setup"
                })
            }
        }
    }
}
