// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import dev.opendistress.shared.DirectConfig
import dev.opendistress.shared.DirectConfigAck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningStateTest {
    @Test
    fun savePublishAndMatchingAckRemainSeparateFacts() {
        val config = fixture(10)
        val saved = ProvisioningState().afterSave(config)
        assertEquals(config, saved.config)
        assertNull(saved.pending?.watchId)
        assertNull(saved.confirmed)

        val published = saved.afterPublish("watch_1234567890")
        assertEquals("watch_1234567890", published.pending?.watchId)
        assertNull(published.confirmed)

        val wrong = DirectConfigAck(
            "watch_1234567890",
            config.revision,
            ByteArray(32),
            1_788_105_600,
        )
        assertEquals(published, published.afterAck(wrong))

        val accepted = DirectConfigAck(
            "watch_1234567890",
            config.revision,
            config.digestSha256(),
            1_788_105_600,
        )
        val confirmed = published.afterAck(accepted)
        assertNull(confirmed.pending)
        assertEquals(config.revision, confirmed.confirmed?.revision)
    }

    @Test
    fun stateCodecRoundTripsPendingAndConfirmedWithoutConflatingThem() {
        val old = fixture(9)
        val oldConfirmed = ProvisioningState(
            config = old,
            confirmed = ConfirmedProvisioning(
                "watch_1234567890",
                old.revision,
                old.digestSha256(),
                1_788_105_600,
            ),
        )
        val next = oldConfirmed.afterSave(fixture(10))
        val decoded = ProvisioningStateCodec.decode(ProvisioningStateCodec.encode(next))

        assertEquals(next, decoded)
        assertEquals(10L, decoded.pending?.revision)
        assertEquals(9L, decoded.confirmed?.revision)
        assertThrows(IllegalArgumentException::class.java) {
            ProvisioningStateCodec.decode(ProvisioningStateCodec.encode(next) + 0)
        }
    }

    @Test
    fun revisionsAreMonotonicAcrossClockRollback() {
        assertEquals(101, nextRevision(100, 50))
        assertEquals(5_000, nextRevision(100, 5_000))
        assertTrue(nextRevision(null, 0) > 0)
        assertThrows(ArithmeticException::class.java) {
            nextRevision(Long.MAX_VALUE, 1)
        }
    }

    private fun fixture(revision: Long) = DirectConfig(
        revision = revision,
        grafanaWebhookUrl =
            "https://tenant.grafana.net/oncall/integrations/v1/formatted_webhook/" + "A".repeat(32) + "/",
        pushoverUserKey = null,
        pushoverApiToken = null,
        protectedPersonName = "Alex",
        customAlertMessage = "Prepared message",
        homeAddress = "Example Street 1",
        childrenInfo = "",
        personDescription = "Blue coat",
        backgroundInfo = "",
        responseInstructions = "Call trusted contact",
        profilePhotoUrl = "",
    )
}
