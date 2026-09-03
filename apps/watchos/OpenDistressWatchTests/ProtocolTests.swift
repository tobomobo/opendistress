// SPDX-License-Identifier: MIT
import Foundation
import XCTest
@testable import OpenDistressWatch

final class ProtocolTests: XCTestCase {
    private let authKey = try! Protocol.decodeHex(
        "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
        count: 32
    )
    private let encryptionKey = try! Protocol.decodeHex(
        "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f",
        count: 32
    )
    private let macKey = try! Protocol.decodeHex(
        "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f",
        count: 32
    )

    func testLiveFixtureConforms() throws {
        let event = try liveFixtureEvent()
        XCTAssertEqual(event.wireJSON(), try fixture("live-trigger-v2.json"))
        XCTAssertEqual(
            event.requestSignature,
            "v2=wKW2UM7B1hOF59JfjpW0ol4YB8sFWccUNT6d_7S28IQ"
        )
        XCTAssertTrue(Protocol.verifyContentTag(event, macKey: macKey))
        let response = Data(
            (
                "{\"v\":2,\"event_id\":\"AAECAwQFBgcICQoLDA0ODw\"," +
                    "\"result\":\"durably_accepted\"," +
                    "\"response_signature\":\"v2=gtYwKUt7qrWFjCrtDJq4yns_1My1J0b67e9cgF7YOKw\"}"
                ).utf8
        )
        XCTAssertTrue(Protocol.verifyAcceptedResponse(response, event: event, authKey: authKey))
    }

    func testLocationFixtureConformsAndTruncatesTowardZero() throws {
        let plaintext = try Protocol.locationBlock(LocationSample(
            captureAt: 1_788_105_650,
            latitude: 12.34567899,
            longitude: -45.67890129,
            quality: 4,
            path: 1
        ))
        XCTAssertEqual(
            plaintext,
            try Protocol.decodeHex("01026a9453b2075bcd15e4c5f3ec0401", count: 16)
        )
        let event = try Protocol.sealEvent(
            eventId: "sLGys7S1tre4ubq7vL2-vw",
            incidentId: "AAECAwQFBgcICQoLDA0ODw",
            deviceId: "EBESExQVFhcYGRobHB0eHw",
            kind: "location.updated",
            sequence: 1,
            createdAt: 1_788_105_660,
            expiresAt: 1_788_109_200,
            keyVersion: 1,
            plaintext: plaintext,
            iv: try Protocol.decodeHex("c0c1c2c3c4c5c6c7c8c9cacbcccdcecf", count: 16),
            authKey: authKey,
            encryptionKey: encryptionKey,
            macKey: macKey
        )
        XCTAssertEqual(event.wireJSON(), try fixture("location-updated-v2.json"))
        XCTAssertEqual(
            event.requestSignature,
            "v2=s84IhlhENf3_q170hFyPLj9g5XKQhYIgfqC-LLc_QXk"
        )
    }

    func testTamperAndMalformedResponsesAreRejected() throws {
        let event = try liveFixtureEvent()
        let tampered = IncidentEvent(
            eventId: event.eventId,
            incidentId: event.incidentId,
            deviceId: event.deviceId,
            kind: event.kind,
            sequence: event.sequence,
            createdAt: event.createdAt,
            expiresAt: event.expiresAt,
            payload: EncryptedPayload(
                keyVersion: event.payload.keyVersion,
                iv: event.payload.iv,
                ciphertext: "8eRa_JOzxdPOO3l494xv5A",
                tag: event.payload.tag
            ),
            requestSignature: event.requestSignature
        )
        XCTAssertFalse(Protocol.verifyContentTag(tampered, macKey: macKey))

        let valid =
            "{\"response_signature\":\"v2=gtYwKUt7qrWFjCrtDJq4yns_1My1J0b67e9cgF7YOKw\"," +
                "\"result\":\"durably_accepted\",\"event_id\":\"AAECAwQFBgcICQoLDA0ODw\",\"v\":2}"
        XCTAssertTrue(Protocol.verifyAcceptedResponse(Data(valid.utf8), event: event, authKey: authKey))
        let invalid = [
            valid.replacingOccurrences(of: "durably_accepted", with: "provider_accepted"),
            String(valid.dropLast()) + ",\"extra\":0}",
            String(valid.dropLast()) + ",\"event_id\":\"AAECAwQFBgcICQoLDA0ODw\"}",
            valid.replacingOccurrences(of: "\"v\":2", with: "\"v\":2e0"),
            valid.replacingOccurrences(of: "event_id", with: "event\\u005fid"),
            valid.replacingOccurrences(of: "gtYwK", with: "AtYwK"),
        ]
        invalid.forEach {
            XCTAssertFalse(Protocol.verifyAcceptedResponse(Data($0.utf8), event: event, authKey: authKey))
        }
        XCTAssertFalse(
            Protocol.verifyAcceptedResponse(Data(repeating: 0x20, count: 513), event: event, authKey: authKey)
        )
    }

    func testStatusFixtureConformsAndTamperingIsRejected() throws {
        let vector = try textVector("status-v2.txt")
        let requestId = try XCTUnwrap(vector["request_id"])
        let incidentId = try XCTUnwrap(vector["incident_id"])
        let deviceId = try XCTUnwrap(vector["device_id"])
        let createdAtText = try XCTUnwrap(vector["created_at"])
        let expiresAtText = try XCTUnwrap(vector["expires_at"])
        let createdAt = try XCTUnwrap(Int64(createdAtText))
        let expiresAt = try XCTUnwrap(Int64(expiresAtText))
        let query = try Protocol.statusQuery(
            requestId: requestId,
            incidentId: incidentId,
            deviceId: deviceId,
            createdAt: createdAt,
            expiresAt: expiresAt,
            authKey: authKey
        )
        XCTAssertEqual(query.wireJSON(), try fixture("status-query-v2.json"))
        XCTAssertEqual(query.requestSignature, vector["request_signature"])
        let response = try XCTUnwrap(vector["response_json"])
        let checkedAtText = try XCTUnwrap(vector["checked_at"])
        let checkedAt = try XCTUnwrap(Int64(checkedAtText))
        XCTAssertEqual(
            Protocol.verifyStatusResponse(
                Data(response.utf8),
                query: query,
                authKey: authKey,
                receivedAt: checkedAt
            ),
            VerifiedIncidentStatus(state: "resolved", checkedAt: checkedAt)
        )
        let invalid = [
            response.replacingOccurrences(of: query.requestId, with: query.incidentId),
            response.replacingOccurrences(of: "resolved", with: "acknowledged"),
            response.replacingOccurrences(of: "7CcJC", with: "ACcJC"),
            String(response.dropLast()) + ",\"extra\":0}",
            String(response.dropLast()) + ",\"state\":\"resolved\"}",
        ]
        for candidate in invalid {
            XCTAssertNil(Protocol.verifyStatusResponse(
                Data(candidate.utf8),
                query: query,
                authKey: authKey,
                receivedAt: checkedAt
            ))
        }
        XCTAssertNil(Protocol.verifyStatusResponse(
            Data(response.utf8),
            query: query,
            authKey: authKey,
            receivedAt: query.createdAt + 301
        ))
        XCTAssertNil(Protocol.verifyStatusResponse(
            Data(repeating: 0, count: 513),
            query: query,
            authKey: authKey,
            receivedAt: query.createdAt
        ))
    }

    @MainActor
    func testAtomicQueuePersistsCiphertextAndOnlyRemovesMatchingHead() throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("opendistress-watch-test-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: url) }
        let live = try liveFixtureEvent()
        let store = try EventStore(fileURL: url)
        try store.startIncident(live)
        XCTAssertFalse(try store.removeMatchingHead(eventId: "sLGys7S1tre4ubq7vL2-vw"))

        let reloaded = try EventStore(fileURL: url)
        XCTAssertEqual(reloaded.state.queue, [live])
        XCTAssertEqual(reloaded.state.capturePlan?.stage, .snapshot)
        XCTAssertTrue(try reloaded.removeMatchingHead(eventId: live.eventId))
        XCTAssertTrue(reloaded.state.queue.isEmpty)
    }

    @MainActor
    func testExpiredIncidentRequiresExplicitArchive() throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("opendistress-watch-archive-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: url) }
        let live = try liveFixtureEvent()
        let store = try EventStore(fileURL: url)
        try store.startIncident(live)
        XCTAssertFalse(store.hasExpiredPending(now: live.expiresAt - 1))
        XCTAssertThrowsError(try store.archiveExpired(now: live.expiresAt - 1))
        XCTAssertTrue(store.hasExpiredPending(now: live.expiresAt))
        try store.archiveExpired(now: live.expiresAt)
        XCTAssertTrue(store.state.queue.isEmpty)
        XCTAssertNil(store.state.capturePlan)
        XCTAssertEqual(store.state.lastArchive?.incidentId, live.incidentId)
        XCTAssertEqual(store.state.lastArchive?.archivedAt, live.expiresAt)
        XCTAssertEqual(store.state.lastArchive?.result, "result_unknown")
    }

    @MainActor
    func testInvalidPersistedCaptureLifetimesAreRejected() throws {
        let invalidPlans = [
            CapturePlan(
                incidentId: "AAECAwQFBgcICQoLDA0ODw",
                deviceId: "EBESExQVFhcYGRobHB0eHw",
                keyVersion: 1,
                expiresAt: 1_000,
                nextSequence: 1,
                stage: .snapshot,
                startedAt: 1_000,
                nextCaptureAt: 1_000,
                lastLatitudeE7: nil,
                lastLongitudeE7: nil,
                lastQuality: 0
            ),
            CapturePlan(
                incidentId: "AAECAwQFBgcICQoLDA0ODw",
                deviceId: "EBESExQVFhcYGRobHB0eHw",
                keyVersion: 1,
                expiresAt: 100_000,
                nextSequence: 1,
                stage: .snapshot,
                startedAt: 1,
                nextCaptureAt: 1,
                lastLatitudeE7: nil,
                lastLongitudeE7: nil,
                lastQuality: 0
            ),
        ]
        for plan in invalidPlans {
            let url = FileManager.default.temporaryDirectory
                .appendingPathComponent("opendistress-watch-invalid-plan-\(UUID().uuidString).json")
            defer { try? FileManager.default.removeItem(at: url) }
            try JSONEncoder().encode(StoredState(capturePlan: plan)).write(to: url, options: .atomic)
            XCTAssertThrowsError(try EventStore(fileURL: url))
        }

        let maximumURL = FileManager.default.temporaryDirectory
            .appendingPathComponent("opendistress-watch-max-archive-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: maximumURL) }
        let maximumArchive = ArchivedIncident(
            incidentId: "AAECAwQFBgcICQoLDA0ODw",
            expiresAt: protocolMaximum,
            archivedAt: protocolMaximum,
            result: "result_unknown"
        )
        try JSONEncoder().encode(StoredState(lastArchive: maximumArchive))
            .write(to: maximumURL, options: .atomic)
        XCTAssertEqual(try EventStore(fileURL: maximumURL).state.lastArchive, maximumArchive)
    }

    @MainActor
    func testRestartAfterExpiryScrubsCoordinatesButPreservesRecoveryEvidence() throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("opendistress-watch-expiry-scrub-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: url) }
        let live = try liveFixtureEvent()
        let archive = ArchivedIncident(
            incidentId: "sLGys7S1tre4ubq7vL2-vw",
            expiresAt: 1_000,
            archivedAt: 1_000,
            result: "result_unknown"
        )
        let plan = CapturePlan(
            incidentId: live.incidentId,
            deviceId: live.deviceId,
            keyVersion: live.payload.keyVersion,
            expiresAt: live.expiresAt,
            nextSequence: 3,
            stage: .followUp,
            startedAt: live.createdAt,
            nextCaptureAt: live.expiresAt,
            lastLatitudeE7: 123_456_789,
            lastLongitudeE7: -456_789_012,
            lastQuality: 4
        )
        try JSONEncoder().encode(StoredState(
            queue: [live],
            capturePlan: plan,
            lastArchive: archive
        )).write(to: url, options: .atomic)

        let loaded = try EventStore(fileURL: url)
        XCTAssertFalse(try loaded.scrubExpiredLocation(now: live.expiresAt - 1))
        XCTAssertTrue(try loaded.scrubExpiredLocation(now: live.expiresAt))
        let restarted = try EventStore(fileURL: url)
        XCTAssertEqual(restarted.state.queue, [live])
        XCTAssertEqual(restarted.state.lastArchive, archive)
        XCTAssertNil(restarted.state.capturePlan?.lastLatitudeE7)
        XCTAssertNil(restarted.state.capturePlan?.lastLongitudeE7)
        XCTAssertEqual(restarted.state.capturePlan?.lastQuality, 0)
    }

    @MainActor
    func testStaleTerminalStatusCannotArchiveANewIncident() throws {
        let url = FileManager.default.temporaryDirectory
            .appendingPathComponent("opendistress-watch-terminal-\(UUID().uuidString).json")
        defer { try? FileManager.default.removeItem(at: url) }
        let old = try liveFixtureEvent()
        let current = try secondLiveFixtureEvent()
        let store = try EventStore(fileURL: url)
        try store.startIncident(old)
        try store.archiveExpired(now: old.expiresAt + 1)
        let resultUnknown = store.state.lastArchive
        try store.startIncident(current)

        XCTAssertFalse(try store.archiveVerifiedTerminalIncident(incidentId: old.incidentId))
        XCTAssertEqual(store.state.queue, [current])
        XCTAssertEqual(store.state.capturePlan?.incidentId, current.incidentId)
        XCTAssertTrue(try store.archiveVerifiedTerminalIncident(incidentId: current.incidentId))
        XCTAssertTrue(store.state.queue.isEmpty)
        XCTAssertNil(store.state.capturePlan)
        XCTAssertEqual(store.state.lastArchive, resultUnknown)
    }

    func testForegroundCadenceAndMaterialChangeAreBounded() {
        XCTAssertEqual(foregroundCadenceSeconds(startedAt: 1_000, now: 1_299, lowBattery: false), 30)
        XCTAssertEqual(foregroundCadenceSeconds(startedAt: 1_000, now: 1_300, lowBattery: false), 120)
        XCTAssertEqual(foregroundCadenceSeconds(startedAt: 1_000, now: 2_800, lowBattery: false), 300)
        XCTAssertEqual(foregroundCadenceSeconds(startedAt: 1_000, now: 2_800, lowBattery: true), 600)
        XCTAssertTrue(canCaptureLocation(now: 999, expiresAt: 1_000))
        XCTAssertFalse(canCaptureLocation(now: 1_000, expiresAt: 1_000))
        XCTAssertFalse(canCaptureLocation(now: 1_001, expiresAt: 1_000))
        let plan = CapturePlan(
            incidentId: "AAECAwQFBgcICQoLDA0ODw",
            deviceId: "EBESExQVFhcYGRobHB0eHw",
            keyVersion: 1,
            expiresAt: 4_600,
            nextSequence: 3,
            stage: .followUp,
            startedAt: 1_000,
            nextCaptureAt: 1_030,
            lastLatitudeE7: 0,
            lastLongitudeE7: 0,
            lastQuality: 3
        )
        XCTAssertFalse(isMaterialLocation(plan: plan, point: LocationPoint(
            latitudeE7: 0,
            longitudeE7: 100,
            quality: 3
        )))
        XCTAssertTrue(isMaterialLocation(plan: plan, point: LocationPoint(
            latitudeE7: 0,
            longitudeE7: 5_000,
            quality: 3
        )))
        XCTAssertTrue(isMaterialLocation(plan: plan, point: LocationPoint(
            latitudeE7: 0,
            longitudeE7: 0,
            quality: 4
        )))
        XCTAssertFalse(isMaterialLocation(plan: plan, point: LocationPoint(
            latitudeE7: nil,
            longitudeE7: nil,
            quality: 0
        )))
    }

    func testStrictBoundsAndUnavailableLocation() throws {
        XCTAssertEqual(
            try Protocol.locationBlock(LocationSample(
                captureAt: 0,
                latitude: nil,
                longitude: nil,
                quality: 0,
                path: 1
            )),
            try Protocol.decodeHex("01020000000000000000000000000001", count: 16)
        )
        XCTAssertThrowsError(try Protocol.locationBlock(LocationSample(
            captureAt: 0,
            latitude: 0,
            longitude: 0,
            quality: 0,
            path: 0
        )))
        XCTAssertThrowsError(try Protocol.sealEvent(
            eventId: "AAECAwQFBgcICQoLDA0ODw",
            incidentId: "AAECAwQFBgcICQoLDA0ODw",
            deviceId: "EBESExQVFhcYGRobHB0eHw",
            kind: "live.triggered",
            sequence: 0,
            createdAt: protocolMaximum + 1,
            expiresAt: protocolMaximum + 1,
            keyVersion: 1,
            plaintext: Data(repeating: 0, count: 16),
            iv: Data(repeating: 0, count: 16),
            authKey: authKey,
            encryptionKey: encryptionKey,
            macKey: macKey
        ))
        let config = RuntimeConfig(
            endpoint: URL(string: "https://relay.example/v2/events")!,
            deviceId: "EBESExQVFhcYGRobHB0eHw",
            authKey: authKey,
            encryptionKey: encryptionKey,
            macKey: macKey,
            keyVersion: 1,
            templateId: Data(repeating: 0, count: 16),
            ttlSeconds: 3_600
        )
        XCTAssertThrowsError(try Protocol.createLocation(
            config: config,
            incidentId: "AAECAwQFBgcICQoLDA0ODw",
            sequence: 1,
            createdAt: 1_000,
            expiresAt: 2_000,
            sample: LocationSample(
                captureAt: 1_001,
                latitude: 0,
                longitude: 0,
                quality: 4,
                path: 1
            )
        ))
        XCTAssertThrowsError(try Protocol.sealEvent(
            eventId: "AAECAwQFBgcICQoLDA0ODw",
            incidentId: "AAECAwQFBgcICQoLDA0ODw",
            deviceId: "EBESExQVFhcYGRobHB0eHw",
            kind: "live.triggered",
            sequence: 0,
            createdAt: 1_000,
            expiresAt: 1_000,
            keyVersion: 1,
            plaintext: Data(repeating: 0, count: 16),
            iv: Data(repeating: 0, count: 16),
            authKey: authKey,
            encryptionKey: encryptionKey,
            macKey: macKey
        ))
        XCTAssertThrowsError(try Protocol.sealEvent(
            eventId: "AAECAwQFBgcICQoLDA0ODw",
            incidentId: "AAECAwQFBgcICQoLDA0ODw",
            deviceId: "EBESExQVFhcYGRobHB0eHw",
            kind: "live.triggered",
            sequence: 0,
            createdAt: 1_000,
            expiresAt: 87_401,
            keyVersion: 1,
            plaintext: Data(repeating: 0, count: 16),
            iv: Data(repeating: 0, count: 16),
            authKey: authKey,
            encryptionKey: encryptionKey,
            macKey: macKey
        ))
    }

    private func liveFixtureEvent() throws -> IncidentEvent {
        try Protocol.sealEvent(
            eventId: "AAECAwQFBgcICQoLDA0ODw",
            incidentId: "AAECAwQFBgcICQoLDA0ODw",
            deviceId: "EBESExQVFhcYGRobHB0eHw",
            kind: "live.triggered",
            sequence: 0,
            createdAt: 1_788_105_600,
            expiresAt: 1_788_109_200,
            keyVersion: 1,
            plaintext: try Protocol.decodeHex("a0a1a2a3a4a5a6a7a8a9aaabacadaeaf", count: 16),
            iv: try Protocol.decodeHex("606162636465666768696a6b6c6d6e6f", count: 16),
            authKey: authKey,
            encryptionKey: encryptionKey,
            macKey: macKey
        )
    }

    private func secondLiveFixtureEvent() throws -> IncidentEvent {
        try Protocol.sealEvent(
            eventId: "sLGys7S1tre4ubq7vL2-vw",
            incidentId: "sLGys7S1tre4ubq7vL2-vw",
            deviceId: "EBESExQVFhcYGRobHB0eHw",
            kind: "live.triggered",
            sequence: 0,
            createdAt: 1_788_105_700,
            expiresAt: 1_788_109_200,
            keyVersion: 1,
            plaintext: Data(repeating: 0x55, count: 16),
            iv: Data(repeating: 0x33, count: 16),
            authKey: authKey,
            encryptionKey: encryptionKey,
            macKey: macKey
        )
    }

    private func fixture(_ name: String) throws -> String {
        let url = try XCTUnwrap(
            Bundle(for: ProtocolTests.self).url(forResource: name, withExtension: nil)
        )
        let data = try Data(contentsOf: url)
        return String(decoding: data, as: UTF8.self).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func textVector(_ name: String) throws -> [String: String] {
        let text = try fixture(name)
        return Dictionary(uniqueKeysWithValues: text.split(separator: "\n").compactMap { line in
            guard !line.hasPrefix("#"), let separator = line.firstIndex(of: "=") else { return nil }
            return (
                String(line[..<separator]),
                String(line[line.index(after: separator)...])
            )
        })
    }
}
