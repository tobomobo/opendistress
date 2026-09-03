// SPDX-License-Identifier: MIT
import CommonCrypto
import CryptoKit
import Foundation
import Security

let protocolMaximum: Int64 = 2_147_483_647
private let uint32Maximum: UInt64 = 4_294_967_295

struct EncryptedPayload: Codable, Equatable {
    let keyVersion: Int64
    let iv: String
    let ciphertext: String
    let tag: String
}

struct IncidentEvent: Codable, Equatable {
    let eventId: String
    let incidentId: String
    let deviceId: String
    let kind: String
    let sequence: Int64
    let createdAt: Int64
    let expiresAt: Int64
    let payload: EncryptedPayload
    let requestSignature: String

    func wireJSON() -> String {
        "{\"v\":2,\"event_id\":\"\(eventId)\",\"incident_id\":\"\(incidentId)\"," +
            "\"device_id\":\"\(deviceId)\",\"kind\":\"\(kind)\",\"sequence\":\(sequence)," +
            "\"created_at\":\(createdAt),\"expires_at\":\(expiresAt),\"payload\":{" +
            "\"key_version\":\(payload.keyVersion),\"iv\":\"\(payload.iv)\"," +
            "\"ciphertext\":\"\(payload.ciphertext)\",\"tag\":\"\(payload.tag)\"}}"
    }
}

struct StatusQuery: Equatable {
    let requestId: String
    let incidentId: String
    let deviceId: String
    let createdAt: Int64
    let expiresAt: Int64
    let requestSignature: String

    func wireJSON() -> String {
        "{\"v\":2,\"request_id\":\"\(requestId)\",\"incident_id\":\"\(incidentId)\"," +
            "\"device_id\":\"\(deviceId)\",\"created_at\":\(createdAt),\"expires_at\":\(expiresAt)}"
    }
}

struct VerifiedIncidentStatus: Equatable {
    let state: String
    let checkedAt: Int64
}

struct LocationSample {
    let captureAt: UInt64
    let latitude: Double?
    let longitude: Double?
    let quality: UInt8
    let path: UInt8
}

struct LocationPoint: Equatable {
    let latitudeE7: Int32?
    let longitudeE7: Int32?
    let quality: UInt8
}

final class RuntimeConfig {
    let endpoint: URL
    let deviceId: String
    let authKey: Data
    let encryptionKey: Data
    let macKey: Data
    let keyVersion: Int64
    let templateId: Data
    let ttlSeconds: Int64

    init(bundle: Bundle = .main) throws {
        func string(_ key: String) throws -> String {
            guard let value = bundle.object(forInfoDictionaryKey: key) as? String else {
                throw ProtocolError.invalidConfiguration
            }
            return value
        }
        guard
            let endpoint = URL(string: try string("OpenDistressEndpoint")),
            let components = URLComponents(url: endpoint, resolvingAgainstBaseURL: false),
            components.scheme == "https",
            components.host != nil,
            components.host != "invalid.example",
            components.user == nil,
            components.password == nil,
            components.query == nil,
            components.fragment == nil,
            components.percentEncodedPath == "/v2/events"
        else {
            throw ProtocolError.invalidConfiguration
        }
        let deviceId = try string("OpenDistressDeviceId")
        try Protocol.validateId(deviceId)
        let auth = try Protocol.decodeHex(try string("OpenDistressAuthKeyHex"), count: 32)
        let encryption = try Protocol.decodeHex(try string("OpenDistressEncKeyHex"), count: 32)
        let mac = try Protocol.decodeHex(try string("OpenDistressMacKeyHex"), count: 32)
        let publishedFixtureKeys = try [
            Protocol.decodeHex(
                "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f",
                count: 32
            ),
            Protocol.decodeHex(
                "202122232425262728292a2b2c2d2e2f303132333435363738393a3b3c3d3e3f",
                count: 32
            ),
            Protocol.decodeHex(
                "404142434445464748494a4b4c4d4e4f505152535455565758595a5b5c5d5e5f",
                count: 32
            ),
        ]
        guard
            auth != encryption,
            auth != mac,
            encryption != mac,
            ![auth, encryption, mac].contains(where: { publishedFixtureKeys.contains($0) })
        else {
            throw ProtocolError.invalidConfiguration
        }
        guard
            let keyVersion = Int64(try string("OpenDistressKeyVersion")),
            (1...protocolMaximum).contains(keyVersion),
            let ttl = Int64(try string("OpenDistressTTLSeconds")),
            (1...86_400).contains(ttl)
        else {
            throw ProtocolError.invalidConfiguration
        }
        self.endpoint = endpoint
        self.deviceId = deviceId
        authKey = auth
        encryptionKey = encryption
        macKey = mac
        self.keyVersion = keyVersion
        templateId = try Protocol.decodeHex(try string("OpenDistressTemplateIdHex"), count: 16)
        ttlSeconds = ttl
    }

    init(
        endpoint: URL,
        deviceId: String,
        authKey: Data,
        encryptionKey: Data,
        macKey: Data,
        keyVersion: Int64,
        templateId: Data,
        ttlSeconds: Int64
    ) {
        self.endpoint = endpoint
        self.deviceId = deviceId
        self.authKey = authKey
        self.encryptionKey = encryptionKey
        self.macKey = macKey
        self.keyVersion = keyVersion
        self.templateId = templateId
        self.ttlSeconds = ttlSeconds
    }
}

enum ProtocolError: Error {
    case invalidConfiguration
    case invalidEvent
    case invalidEncoding
    case cryptoFailure
}

enum Protocol {
    static func randomId() throws -> String {
        base64URL(try randomBytes(count: 16))
    }

    static func validateId(_ value: String) throws {
        _ = try decodeCanonical(value, count: 16, digest: false)
    }

    static func createLive(config: RuntimeConfig, now: Int64) throws -> IncidentEvent {
        guard (0...protocolMaximum).contains(now) else { throw ProtocolError.invalidEvent }
        let (expiresAt, overflow) = now.addingReportingOverflow(config.ttlSeconds)
        guard !overflow, expiresAt <= protocolMaximum else { throw ProtocolError.invalidEvent }
        let incidentId = try randomId()
        var plaintext = config.templateId
        defer { plaintext.resetBytes(in: 0..<plaintext.count) }
        return try sealEvent(
            eventId: incidentId,
            incidentId: incidentId,
            deviceId: config.deviceId,
            kind: "live.triggered",
            sequence: 0,
            createdAt: now,
            expiresAt: expiresAt,
            keyVersion: config.keyVersion,
            plaintext: plaintext,
            iv: randomBytes(count: 16),
            authKey: config.authKey,
            encryptionKey: config.encryptionKey,
            macKey: config.macKey
        )
    }

    static func createLocation(
        config: RuntimeConfig,
        incidentId: String,
        sequence: Int64,
        createdAt: Int64,
        expiresAt: Int64,
        sample: LocationSample
    ) throws -> IncidentEvent {
        guard
            createdAt >= 0,
            sample.captureAt == 0 || sample.captureAt <= UInt64(createdAt)
        else {
            throw ProtocolError.invalidEvent
        }
        var plaintext = try locationBlock(sample)
        defer { plaintext.resetBytes(in: 0..<plaintext.count) }
        return try sealEvent(
            eventId: randomId(),
            incidentId: incidentId,
            deviceId: config.deviceId,
            kind: "location.updated",
            sequence: sequence,
            createdAt: createdAt,
            expiresAt: expiresAt,
            keyVersion: config.keyVersion,
            plaintext: plaintext,
            iv: randomBytes(count: 16),
            authKey: config.authKey,
            encryptionKey: config.encryptionKey,
            macKey: config.macKey
        )
    }

    static func createStatusQuery(
        config: RuntimeConfig,
        plan: CapturePlan,
        now: Int64
    ) throws -> StatusQuery {
        guard plan.deviceId == config.deviceId, plan.keyVersion == config.keyVersion else {
            throw ProtocolError.invalidConfiguration
        }
        var requestId: String
        repeat {
            requestId = try randomId()
        } while requestId == plan.incidentId || requestId == plan.deviceId
        return try statusQuery(
            requestId: requestId,
            incidentId: plan.incidentId,
            deviceId: plan.deviceId,
            createdAt: now,
            expiresAt: plan.expiresAt,
            authKey: config.authKey
        )
    }

    static func statusQuery(
        requestId: String,
        incidentId: String,
        deviceId: String,
        createdAt: Int64,
        expiresAt: Int64,
        authKey: Data
    ) throws -> StatusQuery {
        try validateId(requestId)
        try validateId(incidentId)
        try validateId(deviceId)
        guard
            requestId != incidentId,
            requestId != deviceId,
            (1...protocolMaximum).contains(expiresAt),
            createdAt >= 0,
            createdAt < expiresAt,
            authKey.count == 32
        else {
            throw ProtocolError.invalidEvent
        }
        let signature = base64URL(hmac(authKey, statusQueryCanonical(
            requestId: requestId,
            incidentId: incidentId,
            deviceId: deviceId,
            createdAt: createdAt,
            expiresAt: expiresAt
        )))
        let query = StatusQuery(
            requestId: requestId,
            incidentId: incidentId,
            deviceId: deviceId,
            createdAt: createdAt,
            expiresAt: expiresAt,
            requestSignature: "v2=\(signature)"
        )
        guard query.wireJSON().utf8.count <= 1024 else { throw ProtocolError.invalidEvent }
        return query
    }

    static func sealEvent(
        eventId: String,
        incidentId: String,
        deviceId: String,
        kind: String,
        sequence: Int64,
        createdAt: Int64,
        expiresAt: Int64,
        keyVersion: Int64,
        plaintext: Data,
        iv: Data,
        authKey: Data,
        encryptionKey: Data,
        macKey: Data
    ) throws -> IncidentEvent {
        guard
            plaintext.count == 16,
            iv.count == 16,
            authKey.count == 32,
            encryptionKey.count == 32,
            macKey.count == 32,
            kind == "live.triggered" || kind == "location.updated",
            (0...protocolMaximum).contains(sequence),
            (0...protocolMaximum).contains(createdAt),
            (createdAt...protocolMaximum).contains(expiresAt),
            (1...maximumEventLifetimeSeconds).contains(expiresAt - createdAt),
            (1...protocolMaximum).contains(keyVersion)
        else {
            throw ProtocolError.invalidEvent
        }
        try validateId(eventId)
        try validateId(incidentId)
        try validateId(deviceId)
        if kind == "live.triggered" {
            guard sequence == 0, eventId == incidentId else { throw ProtocolError.invalidEvent }
        } else {
            guard sequence >= 1, eventId != incidentId else { throw ProtocolError.invalidEvent }
        }

        let ivText = base64URL(iv)
        let ciphertextBytes = try aesCBCNoPadding(plaintext, key: encryptionKey, iv: iv)
        guard ciphertextBytes.count == 16 else { throw ProtocolError.cryptoFailure }
        let ciphertext = base64URL(ciphertextBytes)
        let unsigned = EncryptedPayload(
            keyVersion: keyVersion,
            iv: ivText,
            ciphertext: ciphertext,
            tag: ""
        )
        let tag = base64URL(hmac(macKey, contentCanonical(
            eventId: eventId,
            incidentId: incidentId,
            deviceId: deviceId,
            kind: kind,
            sequence: sequence,
            createdAt: createdAt,
            expiresAt: expiresAt,
            payload: unsigned
        )))
        let payload = EncryptedPayload(
            keyVersion: keyVersion,
            iv: ivText,
            ciphertext: ciphertext,
            tag: tag
        )
        let signature = base64URL(hmac(authKey, requestCanonical(
            eventId: eventId,
            incidentId: incidentId,
            deviceId: deviceId,
            kind: kind,
            sequence: sequence,
            createdAt: createdAt,
            expiresAt: expiresAt,
            payload: payload
        )))
        let event = IncidentEvent(
            eventId: eventId,
            incidentId: incidentId,
            deviceId: deviceId,
            kind: kind,
            sequence: sequence,
            createdAt: createdAt,
            expiresAt: expiresAt,
            payload: payload,
            requestSignature: "v2=\(signature)"
        )
        try validateStored(event)
        return event
    }

    static func locationPoint(_ sample: LocationSample) throws -> LocationPoint {
        guard
            sample.captureAt <= uint32Maximum,
            sample.quality <= 4,
            sample.path <= 1
        else {
            throw ProtocolError.invalidEvent
        }
        if sample.quality == 0 {
            guard sample.captureAt == 0, sample.latitude == nil, sample.longitude == nil else {
                throw ProtocolError.invalidEvent
            }
        } else {
            guard sample.captureAt > 0, sample.latitude != nil, sample.longitude != nil else {
                throw ProtocolError.invalidEvent
            }
        }
        let latitude = try scaledCoordinate(sample.latitude, minimum: -90, maximum: 90)
        let longitude = try scaledCoordinate(sample.longitude, minimum: -180, maximum: 180)
        return LocationPoint(
            latitudeE7: sample.quality == 0 ? nil : latitude,
            longitudeE7: sample.quality == 0 ? nil : longitude,
            quality: sample.quality
        )
    }

    static func locationBlock(_ sample: LocationSample) throws -> Data {
        let point = try locationPoint(sample)
        var result = Data([0x01, 0x02])
        result.appendBigEndian(UInt32(sample.captureAt))
        result.appendBigEndian(UInt32(bitPattern: point.latitudeE7 ?? 0))
        result.appendBigEndian(UInt32(bitPattern: point.longitudeE7 ?? 0))
        result.append(sample.quality)
        result.append(sample.path)
        guard result.count == 16 else { throw ProtocolError.invalidEvent }
        return result
    }

    static func validateStored(_ event: IncidentEvent) throws {
        try validateId(event.eventId)
        try validateId(event.incidentId)
        try validateId(event.deviceId)
        guard
            event.kind == "live.triggered" || event.kind == "location.updated",
            (0...protocolMaximum).contains(event.sequence),
            (0...protocolMaximum).contains(event.createdAt),
            (event.createdAt...protocolMaximum).contains(event.expiresAt),
            (1...maximumEventLifetimeSeconds).contains(event.expiresAt - event.createdAt),
            (1...protocolMaximum).contains(event.payload.keyVersion),
            event.requestSignature.hasPrefix("v2="),
            event.wireJSON().utf8.count <= 1024
        else {
            throw ProtocolError.invalidEvent
        }
        _ = try decodeCanonical(event.payload.iv, count: 16, digest: false)
        _ = try decodeCanonical(event.payload.ciphertext, count: 16, digest: false)
        _ = try decodeCanonical(event.payload.tag, count: 32, digest: true)
        _ = try decodeCanonical(String(event.requestSignature.dropFirst(3)), count: 32, digest: true)
        if event.kind == "live.triggered" {
            guard event.sequence == 0, event.eventId == event.incidentId else {
                throw ProtocolError.invalidEvent
            }
        } else {
            guard event.sequence >= 1, event.eventId != event.incidentId else {
                throw ProtocolError.invalidEvent
            }
        }
    }

    static func verifyContentTag(_ event: IncidentEvent, macKey: Data) -> Bool {
        guard macKey.count == 32 else { return false }
        do {
            let supplied = try decodeCanonical(event.payload.tag, count: 32, digest: true)
            return HMAC<SHA256>.isValidAuthenticationCode(
                supplied,
                authenticating: contentCanonical(
                    eventId: event.eventId,
                    incidentId: event.incidentId,
                    deviceId: event.deviceId,
                    kind: event.kind,
                    sequence: event.sequence,
                    createdAt: event.createdAt,
                    expiresAt: event.expiresAt,
                    payload: event.payload
                ),
                using: SymmetricKey(data: macKey)
            )
        } catch {
            return false
        }
    }

    static func verifyAcceptedResponse(_ data: Data, event: IncidentEvent, authKey: Data) -> Bool {
        guard !data.isEmpty, data.count <= 512, authKey.count == 32 else { return false }
        do {
            var parser = try FlatJSONParser(data)
            let fields = try parser.parse()
            guard
                Set(fields.keys) == Set(["v", "event_id", "result", "response_signature"]),
                fields["v"] == JSONScalar(text: "2", quoted: false),
                fields["event_id"] == JSONScalar(text: event.eventId, quoted: true),
                fields["result"] == JSONScalar(text: "durably_accepted", quoted: true),
                let signature = fields["response_signature"],
                signature.quoted,
                signature.text.hasPrefix("v2=")
            else {
                return false
            }
            let supplied = try decodeCanonical(String(signature.text.dropFirst(3)), count: 32, digest: true)
            let canonical = Data(
                (
                    "opendistress.result.v2\n" +
                        "v=2\n" +
                        "event_id=\(event.eventId)\n" +
                        "result=durably_accepted\n"
                    ).utf8
            )
            return HMAC<SHA256>.isValidAuthenticationCode(
                supplied,
                authenticating: canonical,
                using: SymmetricKey(data: authKey)
            )
        } catch {
            return false
        }
    }

    static func verifyStatusResponse(
        _ data: Data,
        query: StatusQuery,
        authKey: Data,
        receivedAt: Int64
    ) -> VerifiedIncidentStatus? {
        guard
            !data.isEmpty,
            data.count <= 512,
            authKey.count == 32,
            (0...protocolMaximum).contains(query.createdAt),
            (1...protocolMaximum).contains(query.expiresAt),
            query.createdAt < query.expiresAt,
            (0...protocolMaximum).contains(receivedAt),
            receivedAt <= query.createdAt + statusClockSkewSeconds
        else {
            return nil
        }
        do {
            var parser = try FlatJSONParser(data)
            let fields = try parser.parse()
            guard
                Set(fields.keys) == Set([
                    "v",
                    "request_id",
                    "incident_id",
                    "device_id",
                    "state",
                    "checked_at",
                    "response_signature",
                ]),
                fields["v"] == JSONScalar(text: "2", quoted: false),
                fields["request_id"] == JSONScalar(text: query.requestId, quoted: true),
                fields["incident_id"] == JSONScalar(text: query.incidentId, quoted: true),
                fields["device_id"] == JSONScalar(text: query.deviceId, quoted: true),
                let stateField = fields["state"],
                stateField.quoted,
                statusStates.contains(stateField.text),
                let checkedField = fields["checked_at"],
                !checkedField.quoted,
                let checkedAt = Int64(checkedField.text),
                (0...protocolMaximum).contains(checkedAt),
                checkedAt >= query.createdAt - statusClockSkewSeconds,
                checkedAt <= receivedAt + statusClockSkewSeconds,
                let signature = fields["response_signature"],
                signature.quoted,
                signature.text.hasPrefix("v2=")
            else {
                return nil
            }
            let supplied = try decodeCanonical(
                String(signature.text.dropFirst(3)),
                count: 32,
                digest: true
            )
            let canonical = statusResponseCanonical(
                requestId: query.requestId,
                incidentId: query.incidentId,
                deviceId: query.deviceId,
                state: stateField.text,
                checkedAt: checkedAt
            )
            guard HMAC<SHA256>.isValidAuthenticationCode(
                supplied,
                authenticating: canonical,
                using: SymmetricKey(data: authKey)
            ) else {
                return nil
            }
            return VerifiedIncidentStatus(state: stateField.text, checkedAt: checkedAt)
        } catch {
            return nil
        }
    }

    private static func statusQueryCanonical(
        requestId: String,
        incidentId: String,
        deviceId: String,
        createdAt: Int64,
        expiresAt: Int64
    ) -> Data {
        Data(
            (
                "opendistress.status.query.v2\n" +
                    "method=POST\n" +
                    "v=2\n" +
                    "request_id=\(requestId)\n" +
                    "incident_id=\(incidentId)\n" +
                    "device_id=\(deviceId)\n" +
                    "created_at=\(createdAt)\n" +
                    "expires_at=\(expiresAt)\n"
                ).utf8
        )
    }

    private static func statusResponseCanonical(
        requestId: String,
        incidentId: String,
        deviceId: String,
        state: String,
        checkedAt: Int64
    ) -> Data {
        Data(
            (
                "opendistress.status.result.v2\n" +
                    "v=2\n" +
                    "request_id=\(requestId)\n" +
                    "incident_id=\(incidentId)\n" +
                    "device_id=\(deviceId)\n" +
                    "state=\(state)\n" +
                    "checked_at=\(checkedAt)\n"
                ).utf8
        )
    }

    static func contentCanonical(
        eventId: String,
        incidentId: String,
        deviceId: String,
        kind: String,
        sequence: Int64,
        createdAt: Int64,
        expiresAt: Int64,
        payload: EncryptedPayload
    ) -> Data {
        Data(
            (
                "opendistress.content.v2\n" +
                    "v=2\n" +
                    "event_id=\(eventId)\n" +
                    "incident_id=\(incidentId)\n" +
                    "device_id=\(deviceId)\n" +
                    "kind=\(kind)\n" +
                    "sequence=\(sequence)\n" +
                    "created_at=\(createdAt)\n" +
                    "expires_at=\(expiresAt)\n" +
                    "payload.key_version=\(payload.keyVersion)\n" +
                    "payload.iv=\(payload.iv)\n" +
                    "payload.ciphertext=\(payload.ciphertext)\n"
                ).utf8
        )
    }

    static func requestCanonical(
        eventId: String,
        incidentId: String,
        deviceId: String,
        kind: String,
        sequence: Int64,
        createdAt: Int64,
        expiresAt: Int64,
        payload: EncryptedPayload
    ) -> Data {
        Data(
            (
                "opendistress.submit.v2\n" +
                    "method=POST\n" +
                    "v=2\n" +
                    "event_id=\(eventId)\n" +
                    "incident_id=\(incidentId)\n" +
                    "device_id=\(deviceId)\n" +
                    "kind=\(kind)\n" +
                    "sequence=\(sequence)\n" +
                    "created_at=\(createdAt)\n" +
                    "expires_at=\(expiresAt)\n" +
                    "payload.key_version=\(payload.keyVersion)\n" +
                    "payload.iv=\(payload.iv)\n" +
                    "payload.ciphertext=\(payload.ciphertext)\n" +
                    "payload.tag=\(payload.tag)\n"
                ).utf8
        )
    }

    static func decodeHex(_ value: String, count: Int) throws -> Data {
        guard value.utf8.count == count * 2 else { throw ProtocolError.invalidEncoding }
        var result = Data(capacity: count)
        var index = value.startIndex
        for _ in 0..<count {
            let next = value.index(index, offsetBy: 2)
            guard let byte = UInt8(value[index..<next], radix: 16) else {
                throw ProtocolError.invalidEncoding
            }
            result.append(byte)
            index = next
        }
        return result
    }

    private static func scaledCoordinate(
        _ value: Double?,
        minimum: Double,
        maximum: Double
    ) throws -> Int32 {
        guard let value else { return 0 }
        guard value.isFinite, (minimum...maximum).contains(value) else {
            throw ProtocolError.invalidEvent
        }
        let scaled = (value * 10_000_000).rounded(.towardZero)
        guard scaled >= Double(Int32.min), scaled <= Double(Int32.max) else {
            throw ProtocolError.invalidEvent
        }
        return Int32(scaled)
    }

    private static func aesCBCNoPadding(_ plaintext: Data, key: Data, iv: Data) throws -> Data {
        // The only C pointer boundary: owned, nonempty buffers stay alive for the
        // complete CCCrypt call and the fixed one-block result is checked below.
        guard plaintext.count == 16, key.count == 32, iv.count == 16 else {
            throw ProtocolError.cryptoFailure
        }
        let outputCapacity = plaintext.count + kCCBlockSizeAES128
        var output = Data(count: outputCapacity)
        var moved = 0
        let status = output.withUnsafeMutableBytes { outputBytes in
            plaintext.withUnsafeBytes { plaintextBytes in
                key.withUnsafeBytes { keyBytes in
                    iv.withUnsafeBytes { ivBytes in
                        CCCrypt(
                            CCOperation(kCCEncrypt),
                            CCAlgorithm(kCCAlgorithmAES),
                            CCOptions(0),
                            keyBytes.baseAddress,
                            key.count,
                            ivBytes.baseAddress,
                            plaintextBytes.baseAddress,
                            plaintext.count,
                            outputBytes.baseAddress,
                            outputCapacity,
                            &moved
                        )
                    }
                }
            }
        }
        guard status == kCCSuccess, moved == plaintext.count else {
            throw ProtocolError.cryptoFailure
        }
        output.removeSubrange(moved..<output.count)
        return output
    }

    private static func hmac(_ key: Data, _ message: Data) -> Data {
        Data(HMAC<SHA256>.authenticationCode(for: message, using: SymmetricKey(data: key)))
    }

    private static func randomBytes(count: Int) throws -> Data {
        var bytes = Data(count: count)
        let status = bytes.withUnsafeMutableBytes { buffer in
            SecRandomCopyBytes(kSecRandomDefault, count, buffer.baseAddress!)
        }
        guard status == errSecSuccess else { throw ProtocolError.cryptoFailure }
        return bytes
    }

    private static func base64URL(_ data: Data) -> String {
        data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
    }

    private static func decodeCanonical(_ value: String, count: Int, digest: Bool) throws -> Data {
        let finalCharacters = digest ? "AEIMQUYcgkosw048" : "AQgw"
        let expectedLength = digest ? 43 : 22
        guard
            value.utf8.count == expectedLength,
            value.utf8.allSatisfy({
                ($0 >= 65 && $0 <= 90) || ($0 >= 97 && $0 <= 122) ||
                    ($0 >= 48 && $0 <= 57) || $0 == 45 || $0 == 95
            }),
            value.last.map({ finalCharacters.contains($0) }) == true
        else {
            throw ProtocolError.invalidEncoding
        }
        var standard = value.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
        standard.append(String(repeating: "=", count: (4 - standard.count % 4) % 4))
        guard
            let decoded = Data(base64Encoded: standard),
            decoded.count == count,
            base64URL(decoded) == value
        else {
            throw ProtocolError.invalidEncoding
        }
        return decoded
    }
}


let maximumEventLifetimeSeconds: Int64 = 86_400
private let statusClockSkewSeconds: Int64 = 300
private let statusStates: Set<String> = ["active", "acknowledged", "resolved", "expired"]

private struct JSONScalar: Equatable {
    let text: String
    let quoted: Bool
}

private struct FlatJSONParser {
    private let bytes: [UInt8]
    private var index = 0

    init(_ data: Data) throws {
        let bytes = Array(data)
        guard bytes.allSatisfy({
            $0 == 0x09 || $0 == 0x0a || $0 == 0x0d || (0x20...0x7e).contains($0)
        }) else {
            throw ProtocolError.invalidEncoding
        }
        self.bytes = bytes
    }

    mutating func parse() throws -> [String: JSONScalar] {
        skipWhitespace()
        try expect(0x7b)
        skipWhitespace()
        var result: [String: JSONScalar] = [:]
        if peek(0x7d) {
            index += 1
        } else {
            while true {
                skipWhitespace()
                let key = try parseString()
                guard result[key] == nil else { throw ProtocolError.invalidEncoding }
                skipWhitespace()
                try expect(0x3a)
                skipWhitespace()
                let scalar: JSONScalar
                if peek(0x22) {
                    scalar = JSONScalar(text: try parseString(), quoted: true)
                } else {
                    scalar = JSONScalar(text: try parseInteger(), quoted: false)
                }
                result[key] = scalar
                skipWhitespace()
                if peek(0x2c) {
                    index += 1
                } else if peek(0x7d) {
                    index += 1
                    break
                } else {
                    throw ProtocolError.invalidEncoding
                }
            }
        }
        skipWhitespace()
        guard index == bytes.count else { throw ProtocolError.invalidEncoding }
        return result
    }

    private mutating func parseString() throws -> String {
        try expect(0x22)
        let start = index
        while index < bytes.count {
            let byte = bytes[index]
            if byte == 0x22 {
                let value = String(decoding: bytes[start..<index], as: UTF8.self)
                index += 1
                return value
            }
            guard byte >= 0x20, byte <= 0x7e, byte != 0x5c else {
                throw ProtocolError.invalidEncoding
            }
            index += 1
        }
        throw ProtocolError.invalidEncoding
    }

    private mutating func parseInteger() throws -> String {
        let start = index
        while index < bytes.count, (0x30...0x39).contains(bytes[index]) {
            index += 1
        }
        guard index > start else { throw ProtocolError.invalidEncoding }
        let value = String(decoding: bytes[start..<index], as: UTF8.self)
        guard value == "0" || !value.hasPrefix("0") else { throw ProtocolError.invalidEncoding }
        return value
    }

    private mutating func skipWhitespace() {
        while index < bytes.count, [0x20, 0x09, 0x0a, 0x0d].contains(bytes[index]) {
            index += 1
        }
    }

    private mutating func expect(_ byte: UInt8) throws {
        guard peek(byte) else { throw ProtocolError.invalidEncoding }
        index += 1
    }

    private func peek(_ byte: UInt8) -> Bool {
        index < bytes.count && bytes[index] == byte
    }
}

private extension Data {
    mutating func appendBigEndian(_ value: UInt32) {
        var bigEndian = value.bigEndian
        Swift.withUnsafeBytes(of: &bigEndian) { append(contentsOf: $0) }
    }
}
