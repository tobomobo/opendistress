// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import dev.opendistress.shared.DirectConfig
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64
import kotlin.math.roundToInt

/** Small, versioned value contract shared with the Connect IQ application. */
internal object GarminCompanionProtocol {
    const val PROTOCOL = "opendistress.companion.v1"
    const val TYPE_CONFIG = "config"
    const val TYPE_CONFIG_ACK = "config_ack"
    const val TYPE_INCIDENT_ACCEPTED = "incident_accepted"
    const val TYPE_LOCATION_CANDIDATE = "location_candidate"
    // The TEST Store build and a later production build are intentionally
    // separate Connect IQ applications. Resolve the installed one at runtime;
    // the public Store listing UUID is not a messaging application ID.
    val GARMIN_APP_IDS = listOf(
        "b9eb9236-66c4-4119-94c5-ba11d891deb0",
        "eab2248e-a772-48c6-9036-f1ec97cf3c24",
    )

    private val configFields: LinkedHashMap<String, DirectConfig.() -> String> = linkedMapOf(
        "grafanaWebhookUrl" to { grafanaWebhookUrl.orEmpty() },
        "pushoverUserKey" to { pushoverUserKey.orEmpty() },
        "pushoverApiToken" to { pushoverApiToken.orEmpty() },
        "protectedPersonName" to { protectedPersonName },
        "customAlertMessage" to { customAlertMessage },
        "homeAddress" to { homeAddress },
        "childrenInfo" to { childrenInfo },
        "personDescription" to { personDescription },
        "backgroundInfo" to { backgroundInfo },
        "responseInstructions" to { responseInstructions },
        "profilePhotoUrl" to { profilePhotoUrl },
    )

    fun configMessage(config: DirectConfig): Map<String, Any> {
        config.validate()
        val values = configFields.mapValues { (_, getter) -> getter(config) }
        return linkedMapOf<String, Any>(
            "protocol" to PROTOCOL,
            "type" to TYPE_CONFIG,
            "revision" to config.revision.toString(),
        ).apply {
            putAll(values)
            put("config_digest", digest(config.revision, values))
        }
    }

    fun digest(config: DirectConfig): String {
        val values = configFields.mapValues { (_, getter) -> getter(config) }
        return digest(config.revision, values)
    }

    fun parseAck(value: Any?): GarminConfigAck? {
        val map = value.asStringMap() ?: return null
        if (map.keys != setOf("protocol", "type", "revision", "config_digest", "stored_at") ||
            map["protocol"] != PROTOCOL || map["type"] != TYPE_CONFIG_ACK
        ) return null
        val revision = map["revision"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val storedAt = map["stored_at"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val digest = map["config_digest"]?.takeIf(::isDigest) ?: return null
        return GarminConfigAck(revision, digest, storedAt)
    }

    fun parseAcceptedIncident(value: Any?): GarminAcceptedIncident? {
        val map = value.asStringMap() ?: return null
        if (map.keys != setOf(
                "protocol", "type", "event_id", "accepted_at", "expires_at", "config_digest",
            ) || map["protocol"] != PROTOCOL || map["type"] != TYPE_INCIDENT_ACCEPTED
        ) return null
        val eventId = map["event_id"]?.takeIf(::isCanonicalId) ?: return null
        val acceptedAt = map["accepted_at"]?.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val expiresAt = map["expires_at"]?.toLongOrNull()
            ?.takeIf { it > acceptedAt && it - acceptedAt <= 86_400 } ?: return null
        val digest = map["config_digest"]?.takeIf(::isDigest) ?: return null
        return GarminAcceptedIncident(eventId, acceptedAt, expiresAt, digest)
    }

    fun locationCandidate(
        incident: GarminAcceptedIncident,
        capturedAtEpochSeconds: Long,
        latitude: Double,
        longitude: Double,
        accuracyMeters: Float,
    ): Map<String, Any> {
        require(capturedAtEpochSeconds in incident.acceptedAtEpochSeconds..incident.expiresAtEpochSeconds)
        require(latitude in -90.0..90.0 && longitude in -180.0..180.0)
        require(accuracyMeters.isFinite() && accuracyMeters in 0f..10_000f)
        return linkedMapOf(
            "protocol" to PROTOCOL,
            "type" to TYPE_LOCATION_CANDIDATE,
            "event_id" to incident.eventId,
            "captured_at" to capturedAtEpochSeconds.toString(),
            "latitude_e7" to (latitude * 10_000_000.0).roundToInt().toString(),
            "longitude_e7" to (longitude * 10_000_000.0).roundToInt().toString(),
            "accuracy_cm" to (accuracyMeters * 100f).roundToInt().toString(),
            "source" to "phone_fused",
            "config_digest" to incident.configDigest,
        )
    }

    private fun digest(revision: Long, values: Map<String, String>): String {
        val canonical = buildString {
            append("opendistress.companion.config.v1\n")
            append("revision=").append(revision).append('\n')
            for ((key, value) in values) {
                append(key).append('=').append(encode(value)).append('\n')
            }
        }
        return encode(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(StandardCharsets.UTF_8)))
    }

    private fun encode(value: String): String = encode(value.toByteArray(StandardCharsets.UTF_8))

    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private fun isDigest(value: String): Boolean =
        value.length == 43 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun isCanonicalId(value: String): Boolean =
        value.length == 22 && value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

    private fun Any?.asStringMap(): Map<String, String>? {
        val raw = this as? Map<*, *> ?: return null
        if (raw.keys.any { it !is String } || raw.values.any { it !is String }) return null
        @Suppress("UNCHECKED_CAST")
        return raw as Map<String, String>
    }
}

internal data class GarminConfigAck(
    val revision: Long,
    val configDigest: String,
    val storedAtEpochSeconds: Long,
)

internal data class GarminAcceptedIncident(
    val eventId: String,
    val acceptedAtEpochSeconds: Long,
    val expiresAtEpochSeconds: Long,
    val configDigest: String,
)
