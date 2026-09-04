// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import dev.opendistress.shared.DirectConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class GarminCompanionProtocolTest {
    private val config = DirectConfig(
        revision = 42,
        grafanaWebhookUrl =
            "https://tenant.grafana.net/oncall/integrations/v1/formatted_webhook/" + "A".repeat(32) + "/",
        pushoverUserKey = null,
        pushoverApiToken = null,
        protectedPersonName = "Alex",
        customAlertMessage = "Line one\nLine two",
        homeAddress = "Example 1",
        childrenInfo = "",
        personDescription = "Blue coat",
        backgroundInfo = "",
        responseInstructions = "Call trusted contact",
        profilePhotoUrl = "",
    )

    @Test
    fun configMessageIsExactAndDigestBindsEveryField() {
        val message = GarminCompanionProtocol.configMessage(config)
        assertEquals(GarminCompanionProtocol.PROTOCOL, message["protocol"])
        assertEquals("42", message["revision"])
        assertEquals(15, message.size)
        assertEquals(GarminCompanionProtocol.digest(config), message["config_digest"])
        // Same fixed vector as companionConfigAndPhoneLocationVectors in Monkey C.
        assertEquals("PtnZRIA3HR75P-8pDXDc6jOBDDG2q9kd_kTMUU6qjAs", message["config_digest"])
        val changed = config.copy(customAlertMessage = "different")
        require(GarminCompanionProtocol.digest(config) != GarminCompanionProtocol.digest(changed))
    }

    @Test
    fun acceptedIncidentAndAckRejectCoercionOrUnknownFields() {
        val accepted = linkedMapOf<String, Any>(
            "protocol" to GarminCompanionProtocol.PROTOCOL,
            "type" to GarminCompanionProtocol.TYPE_INCIDENT_ACCEPTED,
            "event_id" to "AAECAwQFBgcICQoLDA0ODw",
            "accepted_at" to "100",
            "expires_at" to "86500",
            "config_digest" to "A".repeat(43),
        )
        assertEquals(100L, GarminCompanionProtocol.parseAcceptedIncident(accepted)?.acceptedAtEpochSeconds)
        assertNull(GarminCompanionProtocol.parseAcceptedIncident(accepted + ("extra" to "no")))
        assertNull(GarminCompanionProtocol.parseAcceptedIncident(accepted + ("accepted_at" to 100)))

        val ack = mapOf(
            "protocol" to GarminCompanionProtocol.PROTOCOL,
            "type" to GarminCompanionProtocol.TYPE_CONFIG_ACK,
            "revision" to "42",
            "config_digest" to "A".repeat(43),
            "stored_at" to "101",
        )
        assertEquals(42L, GarminCompanionProtocol.parseAck(ack)?.revision)
    }

    @Test
    fun phoneCandidateUsesBoundIntegerTextFields() {
        val incident = GarminAcceptedIncident(
            "AAECAwQFBgcICQoLDA0ODw",
            100,
            86500,
            "A".repeat(43),
        )
        val candidate = GarminCompanionProtocol.locationCandidate(
            incident,
            101,
            48.208174,
            16.373819,
            4.25f,
        )
        assertEquals("482081740", candidate["latitude_e7"])
        assertEquals("163738190", candidate["longitude_e7"])
        assertEquals("425", candidate["accuracy_cm"])
        assertThrows(IllegalArgumentException::class.java) {
            GarminCompanionProtocol.locationCandidate(incident, 99, 48.0, 16.0, 5f)
        }
    }
}
