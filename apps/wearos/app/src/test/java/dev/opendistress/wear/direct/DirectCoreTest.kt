// SPDX-License-Identifier: MIT
package dev.opendistress.wear.direct

import dev.opendistress.shared.DirectConfig
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectCoreTest {
    @Test
    fun providerPayloadsStayClearlyTestOnlyAndBounded() {
        val config = config()
        val grafana = DirectGrafanaAdapter.trigger(config, INCIDENT_ID, 1_000, 1_900)
        assertEquals("application/json", grafana.contentType)
        assertTrue(grafana.body.contains("TESTNOTRUF"))
        assertTrue(grafana.body.contains("KEIN ECHTER NOTFALL"))
        assertTrue(grafana.body.contains("person_description"))
        assertTrue(grafana.body.contains("Person with blue jacket"))

        val pushover = DirectPushoverAdapter.trigger(config, INCIDENT_ID, 1_000, 1_900)
        val form = decodeForm(pushover.body)
        assertEquals("2", form["priority"])
        assertEquals("30", form["retry"])
        assertEquals("900", form["expire"])
        assertTrue(requireNotNull(form["title"]).startsWith("TESTNOTRUF"))
        assertTrue(requireNotNull(form["message"]).startsWith("KEIN ECHTER NOTFALL"))
        assertTrue(requireNotNull(form["message"]).length <= 1_024)
        assertEquals(config.profilePhotoUrl, form["url"])
    }

    @Test
    fun pushoverRequiresEmergencyReceiptAndExactSuccessShape() {
        val accepted = DirectPushoverAdapter.acceptance(
            200,
            "{\"status\":1,\"request\":\"${"C".repeat(30)}\",\"receipt\":\"${"D".repeat(30)}\"}"
                .toByteArray(),
        )
        assertEquals(DirectProvider.PUSHOVER, accepted?.provider)
        assertEquals("D".repeat(30), accepted?.emergencyReceipt)
        assertEquals(null, DirectPushoverAdapter.acceptance(202, byteArrayOf()))
        assertEquals(
            null,
            DirectPushoverAdapter.acceptance(
                200,
                "{\"status\":1,\"request\":\"${"C".repeat(30)}\"}".toByteArray(),
            ),
        )
        assertEquals(
            null,
            DirectPushoverAdapter.acceptance(
                200,
                "{\"status\":1,\"request\":\"${"C".repeat(30)}\",\"receipt\":\"${"D".repeat(30)}\",\"extra\":1}"
                    .toByteArray(),
            ),
        )
    }

    @Test
    fun transportKeepsAcceptanceAndRetryabilitySeparate() {
        val receipt =
            "{\"status\":1,\"request\":\"${"C".repeat(30)}\",\"receipt\":\"${"D".repeat(30)}\"}"
                .toByteArray()
        assertEquals(
            DirectProvider.PUSHOVER,
            DirectProviderTransport.classify(DirectProvider.PUSHOVER, 200, receipt).acceptance?.provider,
        )
        assertEquals(
            null,
            DirectProviderTransport.classify(DirectProvider.PUSHOVER, 200, "{}".toByteArray()).acceptance,
        )
        assertTrue(DirectProviderTransport.classify(DirectProvider.PUSHOVER, 200, "{}".toByteArray()).retryable)
        assertTrue(DirectProviderTransport.classify(DirectProvider.GRAFANA, 503, byteArrayOf()).retryable)
        assertFalse(DirectProviderTransport.classify(DirectProvider.GRAFANA, 400, byteArrayOf()).retryable)
        assertEquals(
            DirectProvider.GRAFANA,
            DirectProviderTransport.classify(DirectProvider.GRAFANA, 204, byteArrayOf()).acceptance?.provider,
        )
    }

    @Test
    fun routeFingerprintBindsEveryCredential() {
        val first = DirectProviderFingerprint.pushover("A".repeat(30), "B".repeat(30))
        assertEquals(first, DirectProviderFingerprint.pushover("A".repeat(30), "B".repeat(30)))
        assertNotEquals(first, DirectProviderFingerprint.pushover("C".repeat(30), "B".repeat(30)))
        assertNotEquals(first, DirectProviderFingerprint.pushover("A".repeat(30), "D".repeat(30)))
        assertNotEquals(
            DirectProviderFingerprint.grafana(config().grafanaWebhookUrl!!),
            DirectProviderFingerprint.grafana(
                "https://tenant.grafana.net/oncall/integrations/v1/formatted_webhook/zyxwvutsrqponmlk/",
            ),
        )
    }

    @Test
    fun locationIsUpdateFirstAndHonestAboutFusedSourceAndAge() {
        val stale = DirectLocationFormatter.format(
            2,
            1_100,
            DirectLocationFix(48.2082, 16.3738, 1_000, 35f, DirectLocationSource.LAST_KNOWN_FUSED),
        )
        assertTrue(stale.title.startsWith("GPS-UPDATE 2"))
        assertTrue(stale.message.startsWith("GPS-UPDATE 2\n\nTESTMODUS — KEIN ECHTER NOTFALL"))
        assertTrue(stale.message.contains("moeglicherweise veraltet"))
        assertTrue(stale.message.contains("Uhr oder verbundenem Handy").not())
        assertEquals(100, stale.ageSeconds)
        assertEquals(true, stale.grafanaFields["gps_may_be_stale"])
        assertEquals(35, stale.grafanaFields["gps_accuracy_meters"])

        val current = DirectLocationFormatter.format(
            1,
            1_005,
            DirectLocationFix(48.2082, 16.3738, 1_000, 10f, DirectLocationSource.CURRENT_FUSED),
        )
        assertFalse(current.mayBeStale)
        assertTrue(current.message.contains("Uhr oder verbundenem Handy"))
        assertEquals("gut", current.quality)
    }

    @Test
    fun durableCodecPreservesImmutableRequestAndAcceptanceStartsTracking() {
        val config = config()
        val grafana = DirectGrafanaAdapter.trigger(config, INCIDENT_ID, 1_000, 1_900)
        val pushover = DirectPushoverAdapter.trigger(config, INCIDENT_ID, 1_000, 1_900)
        val initial = DirectTestState(
            incidentId = INCIDENT_ID,
            createdAt = 1_000,
            triggerExpiresAt = 1_900,
            profileRevision = config.revision,
            routes = listOf(
                DirectRouteState(DirectProvider.GRAFANA, grafana.configurationFingerprint, DirectRouteStatus.PENDING),
                DirectRouteState(DirectProvider.PUSHOVER, pushover.configurationFingerprint, DirectRouteStatus.PENDING),
            ),
            queue = listOf(grafana, pushover),
        )
        val restored = DirectTestStateCodec.decode(DirectTestStateCodec.encode(initial))
        assertEquals(initial, restored)
        assertEquals(grafana, restored.queue.first())

        val accepted = DirectTestTransitions.triggerAccepted(
            restored,
            pushover.requestId,
            DirectProviderAcceptance(DirectProvider.PUSHOVER, "C".repeat(30), "D".repeat(30)),
            1_020,
        )
        assertEquals(1_020L, accepted.acceptedAt)
        assertEquals(87_420L, accepted.trackingExpiresAt)
        assertEquals(listOf(grafana), accepted.queue)
        assertEquals(
            DirectRouteStatus.ACCEPTED,
            accepted.routes.first { it.provider == DirectProvider.PUSHOVER }.status,
        )
        assertThrows(IllegalArgumentException::class.java) {
            DirectTestTransitions.triggerAccepted(
                initial,
                grafana.requestId,
                DirectProviderAcceptance(DirectProvider.PUSHOVER, "C".repeat(30), "D".repeat(30)),
                1_020,
            )
        }
    }

    @Test
    fun encryptedDurableStateRejectsTampering() {
        val cipher = TestStateCipher(ByteArray(32) { it.toByte() })
        val plaintext = "immutable direct request".toByteArray()
        val encrypted = cipher.encrypt(plaintext)
        assertTrue(plaintext.contentEquals(cipher.decrypt(encrypted)))
        encrypted[encrypted.lastIndex] = (encrypted.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { cipher.decrypt(encrypted) }
    }

    private fun config() = DirectConfig(
        revision = 7,
        grafanaWebhookUrl =
            "https://tenant.grafana.net/oncall/integrations/v1/formatted_webhook/abcdefghijklmnop/",
        pushoverUserKey = "A".repeat(30),
        pushoverApiToken = "B".repeat(30),
        protectedPersonName = "Alex Example",
        customAlertMessage = "Prepared test message",
        homeAddress = "Example Street 1",
        childrenInfo = "One child",
        personDescription = "Person with blue jacket",
        backgroundInfo = "Background context",
        responseInstructions = "Call the prepared contact first",
        profilePhotoUrl = "https://example.com/profile.jpg",
    )

    private fun decodeForm(body: String): Map<String, String> = body.split('&').associate { pair ->
        val (key, value) = pair.split('=', limit = 2)
        URLDecoder.decode(key, StandardCharsets.UTF_8.name()) to
            URLDecoder.decode(value, StandardCharsets.UTF_8.name())
    }

    private companion object {
        const val INCIDENT_ID = "AAAAAAAAAAAAAAAAAAAAAA"
    }

    private class TestStateCipher(keyBytes: ByteArray) : DirectStateCipher {
        private val key = SecretKeySpec(keyBytes, "AES")

        override fun encrypt(plaintext: ByteArray): ByteArray {
            val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
            return nonce + cipher.doFinal(plaintext)
        }

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(128, ciphertext.copyOfRange(0, 12)),
            )
            return cipher.doFinal(ciphertext.copyOfRange(12, ciphertext.size))
        }
    }
}
