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
    fun fullProfileRetainsResponsePlanAndAddressWithinProviderLimit() {
        val full = config().copy(
            protectedPersonName = "N".repeat(40), customAlertMessage = "M".repeat(240),
            homeAddress = "H".repeat(120), childrenInfo = "C".repeat(150),
            personDescription = "D".repeat(150), backgroundInfo = "B".repeat(180),
            responseInstructions = "R".repeat(180),
        )
        val message = decodeForm(DirectPushoverAdapter.trigger(full, INCIDENT_ID, 1000, 1900).body)
            .getValue("message")
        assertTrue(message.length <= 1024)
        assertTrue(message.contains("R".repeat(180)))
        assertTrue(message.contains("HEIMADRESSE (NICHT GPS)"))
        assertTrue(message.contains("H".repeat(97)))
        assertTrue(message.indexOf("REAKTIONSPLAN") < message.indexOf("VORBEREITETE NACHRICHT"))
        assertTrue(DirectAlertText.initialMessage(DirectProfile.from(full)).contains(full.responseInstructions))
    }

    @Test
    fun callbackWordsAtTheEndOfTheMaximumBriefingSurviveBothProviders() {
        val words = "Expected: abstract strategy"
        val briefing = "R".repeat(180 - words.length - 2) + "\n\n" + words
        val config = config().copy(responseInstructions = briefing)
        val pushover = decodeForm(DirectPushoverAdapter.trigger(config, INCIDENT_ID, 1000, 1900).body).getValue("message")
        assertTrue(pushover.contains(briefing))
        assertTrue(pushover.startsWith(DirectAlertText.TEST_MESSAGE))
        assertTrue(pushover.contains("keine Polizei verstaendigen"))
        assertTrue(DirectAlertText.initialMessage(DirectProfile.from(config)).contains(briefing))
        assertTrue(DirectGrafanaAdapter.trigger(config, INCIDENT_ID, 1000, 1900).body.contains(words))
    }

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
    fun pushoverUsesOpaqueRequestReferenceAndRequiresReceiptOnlyForEmergencyTrigger() {
        val requestReference = "647d2300-702c-4b38-8b2f-d56326ae460b"
        val trigger = DirectPushoverAdapter.trigger(config(), INCIDENT_ID, 1_000, 1_900)
        val accepted = DirectPushoverAdapter.acceptance(
            200,
            "{\"status\":1,\"request\":\"$requestReference\",\"receipt\":\"${"D".repeat(30)}\"}"
                .toByteArray(),
            DirectRequestKind.TRIGGER,
        )
        assertEquals(DirectProvider.PUSHOVER, accepted?.provider)
        assertEquals(requestReference, accepted?.reference)
        assertEquals("D".repeat(30), accepted?.emergencyReceipt)
        assertEquals(
            null,
            DirectPushoverAdapter.acceptance(202, byteArrayOf(), DirectRequestKind.TRIGGER),
        )
        val rejected = DirectProviderTransport.classify(
            trigger,
            200,
            "{\"user\":\"invalid\",\"errors\":[\"bad user\"],\"status\":0,\"request\":\"$requestReference\"}"
                .toByteArray(),
        )
        assertFalse(rejected.retryable)
        assertEquals(null, rejected.acceptance)
        assertEquals(
            null,
            DirectPushoverAdapter.acceptance(
                200,
                "{\"status\":1,\"request\":\"$requestReference\"}".toByteArray(),
                DirectRequestKind.TRIGGER,
            ),
        )
        val locationAccepted = DirectPushoverAdapter.acceptance(
            200,
            "{\"status\":1,\"request\":\"$requestReference\"}".toByteArray(),
            DirectRequestKind.LOCATION,
        )
        assertEquals(requestReference, locationAccepted?.reference)
        assertEquals(null, locationAccepted?.emergencyReceipt)
    }

    @Test
    fun pushoverCancellationIsReceiptBoundAndUsesOnlyTheApplicationToken() {
        val receipt = "R".repeat(30)
        val cancellation = DirectPushoverAdapter.cancel(
            config(),
            INCIDENT_ID,
            receipt,
            1_100,
            1_900,
        )
        assertEquals(DirectRequestKind.CANCEL, cancellation.kind)
        assertEquals("https://api.pushover.net/1/receipts/$receipt/cancel.json", cancellation.endpoint)
        assertEquals(mapOf("token" to "B".repeat(30)), decodeForm(cancellation.body))
        assertTrue(DirectPushoverAdapter.isCancellationEndpoint(cancellation.endpoint))
        assertFalse(
            DirectPushoverAdapter.isCancellationEndpoint(
                "https://api.pushover.net/1/receipts/${"R".repeat(29)}/cancel.json",
            ),
        )
        val response = "{\"status\":1,\"request\":\"647d2300-702c-4b38-8b2f-d56326ae460b\"}"
            .toByteArray()
        assertEquals(
            DirectProvider.PUSHOVER,
            DirectProviderTransport.classify(cancellation, 200, response).acceptance?.provider,
        )
    }

    @Test
    fun transportKeepsAcceptanceAndRetryabilitySeparate() {
        val trigger = DirectPushoverAdapter.trigger(config(), INCIDENT_ID, 1_000, 1_900)
        val location = DirectPushoverAdapter.location(
            config(),
            INCIDENT_ID,
            1_020,
            2_000,
            DirectLocationFormatter.format(
                1,
                1_020,
                DirectLocationFix(48.2, 16.3, 1_019, 10f, DirectLocationSource.CURRENT_FUSED),
            ),
        )
        val requestReference = "647d2300-702c-4b38-8b2f-d56326ae460b"
        val receipt =
            "{\"status\":1,\"request\":\"$requestReference\",\"receipt\":\"${"D".repeat(30)}\"}"
                .toByteArray()
        assertEquals(
            DirectProvider.PUSHOVER,
            DirectProviderTransport.classify(trigger, 200, receipt).acceptance?.provider,
        )
        assertEquals(
            null,
            DirectProviderTransport.classify(trigger, 200, "{}".toByteArray()).acceptance,
        )
        assertTrue(DirectProviderTransport.classify(trigger, 200, "{}".toByteArray()).retryable)
        assertEquals(
            DirectProvider.PUSHOVER,
            DirectProviderTransport.classify(
                location,
                200,
                "{\"status\":1,\"request\":\"$requestReference\"}".toByteArray(),
            ).acceptance?.provider,
        )
        val grafana = DirectGrafanaAdapter.trigger(config(), INCIDENT_ID, 1_000, 1_900)
        assertTrue(DirectProviderTransport.classify(grafana, 503, byteArrayOf()).retryable)
        assertFalse(DirectProviderTransport.classify(grafana, 400, byteArrayOf()).retryable)
        assertEquals(
            DirectProvider.GRAFANA,
            DirectProviderTransport.classify(grafana, 204, byteArrayOf()).acceptance?.provider,
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
        assertTrue(accepted.queue.isEmpty())
        assertEquals(
            DirectRouteStatus.ACCEPTED,
            accepted.routes.first { it.provider == DirectProvider.PUSHOVER }.status,
        )
        assertEquals(1_920L, accepted.pushoverEmergencyRepeatsUntil())
        assertEquals(
            DirectRouteStatus.SKIPPED,
            accepted.routes.first { it.provider == DirectProvider.GRAFANA }.status,
        )
        assertThrows(IllegalArgumentException::class.java) {
            DirectTestTransitions.triggerAccepted(
                initial,
                grafana.requestId,
                DirectProviderAcceptance(DirectProvider.PUSHOVER, "C".repeat(30), "D".repeat(30)),
                1_020,
            )
        }

        val lateAccepted = DirectTestTransitions.triggerAccepted(
            initial,
            grafana.requestId,
            DirectProviderAcceptance(DirectProvider.GRAFANA, "http_202"),
            1_901,
        )
        assertEquals(1_901L, lateAccepted.acceptedAt)
        assertEquals(88_301L, lateAccepted.trackingExpiresAt)
    }

    @Test
    fun latePushoverAcceptanceTracksProviderSideEmergencyExpiry() {
        val config = config().copy(grafanaWebhookUrl = null)
        val trigger = DirectPushoverAdapter.trigger(config, INCIDENT_ID, 1_000, 1_900)
        val initial = DirectTestState(
            incidentId = INCIDENT_ID,
            createdAt = 1_000,
            triggerExpiresAt = 1_900,
            profileRevision = config.revision,
            routes = listOf(
                DirectRouteState(
                    DirectProvider.PUSHOVER,
                    trigger.configurationFingerprint,
                    DirectRouteStatus.PENDING,
                ),
            ),
            queue = listOf(trigger),
        )

        val accepted = DirectTestTransitions.triggerAccepted(
            initial,
            trigger.requestId,
            DirectProviderAcceptance(
                DirectProvider.PUSHOVER,
                "647d2300-702c-4b38-8b2f-d56326ae460b",
                "D".repeat(30),
            ),
            1_899,
        )

        assertEquals(2_799L, accepted.pushoverEmergencyRepeatsUntil())
        val cancellation = DirectPushoverAdapter.cancel(
            config,
            INCIDENT_ID,
            "D".repeat(30),
            1_901,
            accepted.pushoverEmergencyRepeatsUntil(),
        )
        assertEquals(2_799L, cancellation.expiresAt)
    }

    @Test
    fun grafanaAcceptanceSkipsUnneededPushoverFallback() {
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

        val accepted = DirectTestTransitions.triggerAccepted(
            initial,
            grafana.requestId,
            DirectProviderAcceptance(DirectProvider.GRAFANA, "http_202"),
            1_020,
        )

        assertTrue(accepted.queue.isEmpty())
        assertEquals(
            DirectRouteStatus.SKIPPED,
            accepted.routes.first { it.provider == DirectProvider.PUSHOVER }.status,
        )
    }

    @Test
    fun retryableAttemptRotatesImmutableRequestForProviderFallback() {
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

        val rotated = DirectTestTransitions.retryableAttempt(initial, grafana.requestId)
        assertEquals(listOf(pushover, grafana), rotated.queue)
        assertEquals(grafana, rotated.queue.last())
    }

    @Test
    fun changedPendingRouteDoesNotStarveUnchangedAcceptedLocationRoute() {
        val original = config()
        val grafana = DirectGrafanaAdapter.trigger(original, INCIDENT_ID, 1_000, 1_900)
        val pushover = DirectPushoverAdapter.trigger(original, INCIDENT_ID, 1_000, 1_900)
        val state = DirectTestState(
            incidentId = INCIDENT_ID,
            createdAt = 1_000,
            triggerExpiresAt = 1_900,
            profileRevision = original.revision,
            routes = listOf(
                DirectRouteState(
                    DirectProvider.GRAFANA,
                    grafana.configurationFingerprint,
                    DirectRouteStatus.ACCEPTED,
                    "http_202",
                ),
                DirectRouteState(
                    DirectProvider.PUSHOVER,
                    pushover.configurationFingerprint,
                    DirectRouteStatus.PENDING,
                ),
            ),
            queue = listOf(pushover),
            acceptedAt = 1_010,
            trackingExpiresAt = 87_410,
        )
        val changedPushover = original.copy(
            revision = 8,
            pushoverUserKey = "C".repeat(30),
            pushoverApiToken = "D".repeat(30),
        )

        assertEquals(setOf(DirectProvider.GRAFANA), state.availableLocationProviders(changedPushover))
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
