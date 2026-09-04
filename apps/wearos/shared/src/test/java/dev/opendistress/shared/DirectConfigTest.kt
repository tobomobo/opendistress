// SPDX-License-Identifier: MIT
package dev.opendistress.shared

import java.security.KeyPairGenerator
import java.security.MessageDigest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectConfigTest {
    private val grafana =
        "https://tenant.grafana.net/oncall/integrations/v1/formatted_webhook/" + "A".repeat(32) + "/"

    @Test
    fun canonicalEncodingIsDeterministicAndRoundTripsMultilineProfile() {
        val config = fixture()
        val first = config.canonicalBytes()
        val second = fixture().canonicalBytes()

        assertArrayEquals(first, second)
        assertEquals(config, DirectConfig.fromCanonicalBytes(first))
        assertArrayEquals(
            MessageDigest.getInstance("SHA-256").digest(first),
            config.digestSha256(),
        )
        assertTrue(DirectConfig.fromCanonicalBytes(first).backgroundInfo.contains('\n'))
    }

    @Test
    fun providerAndProfileValidationAreStrict() {
        assertThrows(IllegalArgumentException::class.java) {
            fixture().copy(grafanaWebhookUrl = null, pushoverUserKey = null, pushoverApiToken = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture().copy(pushoverApiToken = null)
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture().copy(grafanaWebhookUrl = "https://example.com/secret/")
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture().copy(protectedPersonName = "x".repeat(41))
        }
        assertThrows(IllegalArgumentException::class.java) {
            fixture().copy(profilePhotoUrl = "https://user@example.com/photo.jpg")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DirectConfig.fromCanonicalBytes(fixture().canonicalBytes() + 0)
        }
    }

    @Test
    fun envelopeRoundTripsAndRejectsTampering() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val announcement = WatchKeyAnnouncement("watch_1234567890", 7, keyPair.public.encoded)
        val config = fixture()
        val envelope = ProvisioningCrypto.seal(config, announcement)
        val encoded = envelope.canonicalBytes()

        assertEquals(envelope, DirectConfigEnvelope.fromCanonicalBytes(encoded))
        assertEquals(config, ProvisioningCrypto.open(envelope, keyPair.private, announcement))
        assertEquals(config.revision, envelope.configRevision)
        assertArrayEquals(config.digestSha256(), envelope.configDigestSha256)

        val tampered = envelope.copy(ciphertext = envelope.ciphertext.clone().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        })
        assertThrows(Exception::class.java) {
            ProvisioningCrypto.open(tampered, keyPair.private)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ProvisioningCrypto.open(
                envelope,
                keyPair.private,
                announcement.copy(keyVersion = announcement.keyVersion + 1),
            )
        }
    }

    @Test
    fun announcementsAcksAndPathsAreBounded() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val announcement = WatchKeyAnnouncement("watch_1234567890", 1, keyPair.public.encoded)
        assertEquals(
            announcement,
            WatchKeyAnnouncement.fromCanonicalBytes(announcement.canonicalBytes()),
        )
        val ack = DirectConfigAck(
            announcement.watchId,
            4,
            ByteArray(32) { it.toByte() },
            1_788_105_600,
        )
        assertEquals(ack, DirectConfigAck.fromCanonicalBytes(ack.canonicalBytes()))
        val keyRequest = WatchKeyRequest(ByteArray(16) { (it + 1).toByte() }, 1_788_105_600_000)
        assertEquals(
            keyRequest,
            WatchKeyRequest.fromCanonicalBytes(keyRequest.canonicalBytes()),
        )
        assertEquals(
            "/opendistress/provisioning/v1/watch-key-request",
            ProvisioningPaths.KEY_REQUEST,
        )
        assertEquals(
            announcement.watchId,
            ProvisioningPaths.watchIdFrom(
                ProvisioningPaths.watchKey(announcement.watchId),
                ProvisioningPaths.WATCH_KEY_PREFIX,
            ),
        )
        assertFalse(ProvisioningPaths.config(announcement.watchId).contains("//"))
        assertThrows(IllegalArgumentException::class.java) {
            ProvisioningPaths.ack("../../other-app")
        }
    }

    private fun fixture() = DirectConfig(
        revision = 4,
        grafanaWebhookUrl = grafana,
        pushoverUserKey = "U".repeat(30),
        pushoverApiToken = "T".repeat(30),
        protectedPersonName = "Alex Example",
        customAlertMessage = "Prepared test alert",
        homeAddress = "Example Street 1",
        childrenInfo = "Two children; call family",
        personDescription = "Blue coat",
        backgroundInfo = "Risk context\nSecond line",
        responseInstructions = "Call first; verify independently",
        profilePhotoUrl = "https://images.example/profile.jpg",
    )
}
