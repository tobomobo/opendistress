// SPDX-License-Identifier: MIT
package dev.opendistress.mobile

import dev.opendistress.shared.DirectConfig
import dev.opendistress.shared.DirectConfigAck
import dev.opendistress.shared.WatchKeyAnnouncement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProvisioningStateTest {
    @Test
    fun drillEvidenceIsBoundToSetupAndExpiresWithoutInventingTelemetry() {
        val drill = DrillEvidence(10, "Garmin", "Pushover", 1000)
        assertTrue(drill.isCurrent(10, 1001))
        assertTrue(!drill.isCurrent(11, 1001))
        assertTrue(!drill.isCurrent(10, 999))
        assertTrue(!drill.isCurrent(10, 1000 + 30L * 86400))
        val state = ProvisioningState(config = fixture(10), drills = listOf(drill))
        assertEquals(state, ProvisioningStateCodec.decode(ProvisioningStateCodec.encode(state)))
        assertNull(state.confirmed)
        assertTrue(!state.afterSave(fixture(11)).drills.single().isCurrent(11, 1001))
    }

    @Test
    fun oldStateMigratesWithoutClaimingADrill() {
        val state = ProvisioningState(config = fixture(10))
        val versionThree = ProvisioningStateCodec.encode(state)
        // Version 1 ends before the new drill count; preserve its original layout.
        val old = versionThree.copyOf(versionThree.size - 8)
        java.nio.ByteBuffer.wrap(old).putInt(4, 1)
        assertEquals(state, ProvisioningStateCodec.decode(old))
    }

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

    @Test
    fun watchSelectionKeepsOnlyAnnouncementsCreatedByConnectedNodes() {
        val first = sourced("node-a", "watch_1234567890")
        val second = sourced("node-b", "watch_0987654321")
        val stale = sourced("node-stale", "watch_1111111111")

        assertEquals(
            listOf(first),
            connectedWatchAnnouncements(listOf(stale, second, first), setOf("node-a")),
        )
        assertEquals(
            2,
            connectedWatchAnnouncements(listOf(stale, second, first), setOf("node-a", "node-b")).size,
        )
        assertTrue(connectedWatchAnnouncements(listOf(stale), setOf("node-a")).isEmpty())
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

    private fun sourced(nodeId: String, watchId: String) = SourcedWatchAnnouncement(
        creatorNodeId = nodeId,
        announcement = WatchKeyAnnouncement(watchId, 1, ByteArray(256) { 1 }),
    )
}
