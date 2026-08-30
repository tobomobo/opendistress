// SPDX-License-Identifier: MIT
package dev.smartpanic.wear

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private val authKey = Protocol.decodeHex(
        "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
        32,
    )
    private val encryptionKey = Protocol.decodeHex(
        "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f",
        32,
    )
    private val macKey = Protocol.decodeHex(
        "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f",
        32,
    )

    @Test
    fun liveFixtureConforms() {
        val event = liveFixtureEvent()
        assertEquals(readFixture("live-trigger-v2.json"), event.wireJson())
        assertEquals(
            "v2=vkHWr3fYtcYij4GqeJJ49dJhDn38m26ifCTJAU3SknY",
            event.requestSignature,
        )
        assertTrue(Protocol.verifyContentTag(event, macKey))
        assertTrue(
            Protocol.verifyAcceptedResponse(
                (
                    "{\"v\":2,\"event_id\":\"AAECAwQFBgcICQoLDA0ODw\"," +
                        "\"result\":\"durably_accepted\"," +
                        "\"response_signature\":\"v2=Z40vnSWhJ7rbDRz6kO8nAh8-Qen5RGpl20xiiQ6kCpI\"}"
                    ).toByteArray(),
                event,
                authKey,
            ),
        )
    }

    @Test
    fun locationFixtureConformsAndTruncatesTowardZero() {
        val plaintext = Protocol.locationBlock(
            LocationSample(
                captureAt = 1_788_105_650,
                latitude = 12.34567899,
                longitude = -45.67890129,
                quality = 4,
                path = 1,
            ),
        )
        assertArrayEquals(
            Protocol.decodeHex("01026a9453b2075bcd15e4c5f3ec0401", 16),
            plaintext,
        )
        val event = Protocol.sealEvent(
            eventId = "sLGys7S1tre4ubq7vL2-vw",
            incidentId = "AAECAwQFBgcICQoLDA0ODw",
            deviceId = "EBESExQVFhcYGRobHB0eHw",
            kind = "location.updated",
            sequence = 1,
            createdAt = 1_788_105_660,
            expiresAt = 1_788_109_200,
            keyVersion = 1,
            plaintext = plaintext,
            ivBytes = Protocol.decodeHex("c0c1c2c3c4c5c6c7c8c9cacbcccdcecf", 16),
            authKey = authKey,
            encryptionKey = encryptionKey,
            macKey = macKey,
        )
        assertEquals(readFixture("location-updated-v2.json"), event.wireJson())
        assertEquals(
            "v2=uGLHdOkt0pA1daHA313hWEMUI2pdB5mQNuwQOB_uTM8",
            event.requestSignature,
        )
        assertTrue(Protocol.verifyContentTag(event, macKey))
    }

    @Test
    fun tamperAndInvalidResponsesAreRejected() {
        val event = liveFixtureEvent()
        val tampered = event.copy(
            payload = event.payload.copy(ciphertext = "8eRa_JOzxdPOO3l494xv5A"),
        )
        assertFalse(Protocol.verifyContentTag(tampered, macKey))

        val validReordered =
            "{\"response_signature\":\"v2=Z40vnSWhJ7rbDRz6kO8nAh8-Qen5RGpl20xiiQ6kCpI\"," +
                "\"result\":\"durably_accepted\",\"event_id\":\"AAECAwQFBgcICQoLDA0ODw\",\"v\":2}"
        assertTrue(Protocol.verifyAcceptedResponse(validReordered.toByteArray(), event, authKey))

        val invalid = listOf(
            validReordered.replace("durably_accepted", "provider_accepted"),
            validReordered.dropLast(1) + ",\"extra\":0}",
            validReordered.dropLast(1) + ",\"event_id\":\"AAECAwQFBgcICQoLDA0ODw\"}",
            validReordered.replace("\"v\":2", "\"v\":2e0"),
            validReordered.replace("event_id", "event\\u005fid"),
            validReordered.replace("Z40vn", "A40vn"),
        )
        invalid.forEach { response ->
            assertFalse(Protocol.verifyAcceptedResponse(response.toByteArray(), event, authKey))
        }
        assertFalse(Protocol.verifyAcceptedResponse(ByteArray(513) { ' '.code.toByte() }, event, authKey))
    }

    @Test
    fun statusFixtureConformsAndTamperingIsRejected() {
        val vector = readVector("status-v2.txt")
        val query = Protocol.statusQuery(
            requestId = vector.getValue("request_id"),
            incidentId = vector.getValue("incident_id"),
            deviceId = vector.getValue("device_id"),
            createdAt = vector.getValue("created_at").toLong(),
            expiresAt = vector.getValue("expires_at").toLong(),
            authKey = authKey,
        )
        assertEquals(readFixture("status-query-v2.json"), query.wireJson())
        assertEquals(vector.getValue("request_signature"), query.requestSignature)
        val response = vector.getValue("response_json")
        assertEquals(
            VerifiedIncidentStatus("resolved", vector.getValue("checked_at").toLong()),
            Protocol.verifyStatusResponse(
                response.toByteArray(),
                query,
                authKey,
                vector.getValue("checked_at").toLong(),
            ),
        )
        val invalid = listOf(
            response.replace(query.requestId, "AAECAwQFBgcICQoLDA0ODw"),
            response.replace("resolved", "acknowledged"),
            response.replace("1PKgg", "APKgg"),
            response.dropLast(1) + ",\"extra\":0}",
            response.dropLast(1) + ",\"state\":\"resolved\"}",
        )
        invalid.forEach {
            assertEquals(null, Protocol.verifyStatusResponse(
                it.toByteArray(),
                query,
                authKey,
                vector.getValue("checked_at").toLong(),
            ))
        }
        assertEquals(null, Protocol.verifyStatusResponse(
            response.toByteArray(),
            query,
            authKey,
            query.createdAt + 301,
        ))
        assertEquals(null, Protocol.verifyStatusResponse(ByteArray(513), query, authKey, query.createdAt))
    }

    @Test
    fun strictBoundsAndUnavailableLocationAreEnforced() {
        assertArrayEquals(
            Protocol.decodeHex("01020000000000000000000000000001", 16),
            Protocol.locationBlock(LocationSample(0, null, null, 0, 1)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            Protocol.locationBlock(LocationSample(0, 0.0, 0.0, 0, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Protocol.sealEvent(
                eventId = "AAECAwQFBgcICQoLDA0ODw",
                incidentId = "AAECAwQFBgcICQoLDA0ODw",
                deviceId = "EBESExQVFhcYGRobHB0eHw",
                kind = "live.triggered",
                sequence = 0,
                createdAt = PROTOCOL_MAX + 1,
                expiresAt = PROTOCOL_MAX + 1,
                keyVersion = 1,
                plaintext = ByteArray(16),
                ivBytes = ByteArray(16),
                authKey = authKey,
                encryptionKey = encryptionKey,
                macKey = macKey,
            )
        }
        val config = RuntimeConfig(
            endpoint = java.net.URL("https://relay.example/v2/events"),
            deviceId = "EBESExQVFhcYGRobHB0eHw",
            authKey = authKey,
            encryptionKey = encryptionKey,
            macKey = macKey,
            keyVersion = 1,
            templateId = ByteArray(16),
            ttlSeconds = 3_600,
        )
        assertThrows(IllegalArgumentException::class.java) {
            Protocol.createLocation(
                config = config,
                incidentId = "AAECAwQFBgcICQoLDA0ODw",
                sequence = 1,
                createdAt = 1_000,
                expiresAt = 2_000,
                sample = LocationSample(1_001, 0.0, 0.0, 4, 1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Protocol.sealEvent(
                eventId = "AAECAwQFBgcICQoLDA0ODw",
                incidentId = "AAECAwQFBgcICQoLDA0ODw",
                deviceId = "EBESExQVFhcYGRobHB0eHw",
                kind = "live.triggered",
                sequence = 0,
                createdAt = 1_000,
                expiresAt = 1_000,
                keyVersion = 1,
                plaintext = ByteArray(16),
                ivBytes = ByteArray(16),
                authKey = authKey,
                encryptionKey = encryptionKey,
                macKey = macKey,
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Protocol.sealEvent(
                eventId = "AAECAwQFBgcICQoLDA0ODw",
                incidentId = "AAECAwQFBgcICQoLDA0ODw",
                deviceId = "EBESExQVFhcYGRobHB0eHw",
                kind = "live.triggered",
                sequence = 0,
                createdAt = 1_000,
                expiresAt = 87_401,
                keyVersion = 1,
                plaintext = ByteArray(16),
                ivBytes = ByteArray(16),
                authKey = authKey,
                encryptionKey = encryptionKey,
                macKey = macKey,
            )
        }
    }

    @Test
    fun foregroundCadenceAndMaterialChangeAreBounded() {
        assertEquals(30L, foregroundCadenceSeconds(1_000, 1_299, false))
        assertEquals(120L, foregroundCadenceSeconds(1_000, 1_300, false))
        assertEquals(300L, foregroundCadenceSeconds(1_000, 2_800, false))
        assertEquals(600L, foregroundCadenceSeconds(1_000, 2_800, true))
        assertTrue(canCaptureLocation(999, 1_000))
        assertFalse(canCaptureLocation(1_000, 1_000))
        assertFalse(canCaptureLocation(1_001, 1_000))

        val plan = CapturePlan(
            incidentId = "AAECAwQFBgcICQoLDA0ODw",
            deviceId = "EBESExQVFhcYGRobHB0eHw",
            keyVersion = 1,
            expiresAt = 4_600,
            nextSequence = 3,
            stage = CaptureStage.FOLLOW_UP,
            startedAt = 1_000,
            nextCaptureAt = 1_030,
            lastLatitudeE7 = 0,
            lastLongitudeE7 = 0,
            lastQuality = 3,
        )
        assertFalse(isMaterialLocation(plan, LocationPoint(0, 100, 3)))
        assertTrue(isMaterialLocation(plan, LocationPoint(0, 5_000, 3)))
        assertTrue(isMaterialLocation(plan, LocationPoint(0, 0, 4)))
        assertFalse(isMaterialLocation(plan, LocationPoint(null, null, 0)))
    }

    @Test
    fun staleTerminalStatusCannotArchiveANewIncident() {
        val old = liveFixtureEvent()
        val current = secondLiveFixtureEvent()
        val archive = ArchivedIncident(old.incidentId, old.expiresAt, old.expiresAt + 1)
        val plan = CapturePlan(
            incidentId = current.incidentId,
            deviceId = current.deviceId,
            keyVersion = current.payload.keyVersion,
            expiresAt = current.expiresAt,
            nextSequence = 1,
            stage = CaptureStage.SNAPSHOT,
            startedAt = current.createdAt,
            nextCaptureAt = current.createdAt,
            lastLatitudeE7 = null,
            lastLongitudeE7 = null,
            lastQuality = 0,
        )
        val state = StoredState(listOf(current), plan, archive)
        assertEquals(null, stateAfterVerifiedTerminal(state, old.incidentId))
        val terminal = requireNotNull(stateAfterVerifiedTerminal(state, current.incidentId))
        assertTrue(terminal.queue.isEmpty())
        assertEquals(null, terminal.capturePlan)
        assertEquals(archive, terminal.lastArchive)
    }

    @Test
    fun persistedCapturePlanLifetimeIsStrict() {
        fun plan(startedAt: Long, expiresAt: Long) = CapturePlan(
            incidentId = "AAECAwQFBgcICQoLDA0ODw",
            deviceId = "EBESExQVFhcYGRobHB0eHw",
            keyVersion = 1,
            expiresAt = expiresAt,
            nextSequence = 1,
            stage = CaptureStage.SNAPSHOT,
            startedAt = startedAt,
            nextCaptureAt = startedAt,
            lastLatitudeE7 = null,
            lastLongitudeE7 = null,
            lastQuality = 0,
        )
        assertThrows(IllegalArgumentException::class.java) { validateCapturePlan(plan(1_000, 1_000)) }
        assertThrows(IllegalArgumentException::class.java) { validateCapturePlan(plan(1, 100_000)) }
        validateCapturePlan(plan(PROTOCOL_MAX - 1, PROTOCOL_MAX))
        validateArchivedIncident(ArchivedIncident(
            incidentId = "AAECAwQFBgcICQoLDA0ODw",
            expiresAt = PROTOCOL_MAX,
            archivedAt = PROTOCOL_MAX,
        ))
    }

    @Test
    fun loadedExpiredStateScrubsCoordinatesButPreservesRecoveryEvidence() {
        val live = liveFixtureEvent()
        val archive = ArchivedIncident(
            incidentId = "sLGys7S1tre4ubq7vL2-vw",
            expiresAt = 1_000,
            archivedAt = 1_000,
        )
        val plan = CapturePlan(
            incidentId = live.incidentId,
            deviceId = live.deviceId,
            keyVersion = live.payload.keyVersion,
            expiresAt = live.expiresAt,
            nextSequence = 3,
            stage = CaptureStage.FOLLOW_UP,
            startedAt = live.createdAt,
            nextCaptureAt = live.expiresAt,
            lastLatitudeE7 = 123_456_789,
            lastLongitudeE7 = -456_789_012,
            lastQuality = 4,
        )
        val loaded = StoredState(listOf(live), plan, archive)
        assertEquals(null, stateAfterExpiredLocationScrub(loaded, live.expiresAt - 1))

        val scrubbed = requireNotNull(stateAfterExpiredLocationScrub(loaded, live.expiresAt))
        assertEquals(listOf(live), scrubbed.queue)
        assertEquals(archive, scrubbed.lastArchive)
        assertEquals(null, scrubbed.capturePlan?.lastLatitudeE7)
        assertEquals(null, scrubbed.capturePlan?.lastLongitudeE7)
        assertEquals(0, scrubbed.capturePlan?.lastQuality)
        validateCapturePlan(requireNotNull(scrubbed.capturePlan))
    }

    private fun liveFixtureEvent(): IncidentEvent = Protocol.sealEvent(
        eventId = "AAECAwQFBgcICQoLDA0ODw",
        incidentId = "AAECAwQFBgcICQoLDA0ODw",
        deviceId = "EBESExQVFhcYGRobHB0eHw",
        kind = "live.triggered",
        sequence = 0,
        createdAt = 1_788_105_600,
        expiresAt = 1_788_109_200,
        keyVersion = 1,
        plaintext = Protocol.decodeHex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf", 16),
        ivBytes = Protocol.decodeHex("606162636465666768696a6b6c6d6e6f", 16),
        authKey = authKey,
        encryptionKey = encryptionKey,
        macKey = macKey,
    )

    private fun secondLiveFixtureEvent(): IncidentEvent = Protocol.sealEvent(
        eventId = "sLGys7S1tre4ubq7vL2-vw",
        incidentId = "sLGys7S1tre4ubq7vL2-vw",
        deviceId = "EBESExQVFhcYGRobHB0eHw",
        kind = "live.triggered",
        sequence = 0,
        createdAt = 1_788_105_700,
        expiresAt = 1_788_109_200,
        keyVersion = 1,
        plaintext = ByteArray(16) { 0x55.toByte() },
        ivBytes = ByteArray(16) { 0x33.toByte() },
        authKey = authKey,
        encryptionKey = encryptionKey,
        macKey = macKey,
    )

    private fun readFixture(name: String): String {
        val root = Path.of(requireNotNull(System.getProperty("spb.repo.root")))
        return Files.readString(root.resolve("protocol/fixtures/$name")).trimEnd()
    }

    private fun readVector(name: String): Map<String, String> {
        val root = Path.of(requireNotNull(System.getProperty("spb.repo.root")))
        return Files.readAllLines(root.resolve("protocol/fixtures/$name"))
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }
}
