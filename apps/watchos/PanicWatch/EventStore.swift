// SPDX-License-Identifier: MIT
import Foundation

enum CaptureStage: String, Codable {
    case snapshot
    case fresh
    case followUp
}

struct CapturePlan: Codable, Equatable {
    let incidentId: String
    let deviceId: String
    let keyVersion: Int64
    let expiresAt: Int64
    let nextSequence: Int64
    let stage: CaptureStage
    let startedAt: Int64
    let nextCaptureAt: Int64
    let lastLatitudeE7: Int32?
    let lastLongitudeE7: Int32?
    let lastQuality: UInt8
}

struct ArchivedIncident: Codable, Equatable {
    let incidentId: String
    let expiresAt: Int64
    let archivedAt: Int64
    let result: String
}

struct StoredState: Codable, Equatable {
    var queue: [IncidentEvent] = []
    var capturePlan: CapturePlan? = nil
    var lastArchive: ArchivedIncident? = nil
}

@MainActor
final class EventStore {
    private(set) var state: StoredState
    private let fileURL: URL
    private let encoder: JSONEncoder

    init(fileURL: URL? = nil) throws {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]
        self.encoder = encoder
        if let fileURL {
            self.fileURL = fileURL
        } else {
            let directory = try FileManager.default.url(
                for: .applicationSupportDirectory,
                in: .userDomainMask,
                appropriateFor: nil,
                create: true
            ).appendingPathComponent("incident-v2", isDirectory: true)
            try FileManager.default.createDirectory(
                at: directory,
                withIntermediateDirectories: true
            )
            self.fileURL = directory.appendingPathComponent("queue.json")
        }
        state = try Self.read(from: self.fileURL)
        try validate(state)
    }

    func startIncident(_ event: IncidentEvent) throws {
        guard state.queue.isEmpty, state.capturePlan == nil, event.kind == "live.triggered" else {
            throw ProtocolError.invalidEvent
        }
        try replace(StoredState(
            queue: [event],
            capturePlan: CapturePlan(
                incidentId: event.incidentId,
                deviceId: event.deviceId,
                keyVersion: event.payload.keyVersion,
                expiresAt: event.expiresAt,
                nextSequence: 1,
                stage: .snapshot,
                startedAt: event.createdAt,
                nextCaptureAt: event.createdAt,
                lastLatitudeE7: nil,
                lastLongitudeE7: nil,
                lastQuality: 0
            ),
            lastArchive: state.lastArchive
        ))
    }

    func appendSnapshot(_ event: IncidentEvent, point: LocationPoint) throws {
        guard let plan = state.capturePlan, plan.stage == .snapshot else {
            throw ProtocolError.invalidEvent
        }
        try requireMatches(event, plan: plan)
        let (nextSequence, overflow) = plan.nextSequence.addingReportingOverflow(1)
        guard !overflow, nextSequence <= protocolMaximum else { throw ProtocolError.invalidEvent }
        var next = state
        next.queue.append(event)
        next.capturePlan = planWithPoint(
            plan,
            point: point,
            nextSequence: nextSequence,
            stage: .fresh,
            nextCaptureAt: plan.nextCaptureAt
        )
        try replace(next)
    }

    func appendFresh(
        _ event: IncidentEvent,
        point: LocationPoint,
        nextCaptureAt: Int64
    ) throws {
        guard let plan = state.capturePlan, plan.stage == .fresh else {
            throw ProtocolError.invalidEvent
        }
        try requireMatches(event, plan: plan)
        let (nextSequence, overflow) = plan.nextSequence.addingReportingOverflow(1)
        guard !overflow, nextSequence <= protocolMaximum else { throw ProtocolError.invalidEvent }
        var next = state
        next.queue.append(event)
        next.capturePlan = planWithPoint(
            plan,
            point: point,
            nextSequence: nextSequence,
            stage: .followUp,
            nextCaptureAt: nextCaptureAt
        )
        try replace(next)
    }

    func appendFollowUp(
        _ event: IncidentEvent,
        point: LocationPoint,
        nextCaptureAt: Int64
    ) throws {
        guard let plan = state.capturePlan, plan.stage == .followUp else {
            throw ProtocolError.invalidEvent
        }
        try requireMatches(event, plan: plan)
        let (nextSequence, overflow) = plan.nextSequence.addingReportingOverflow(1)
        guard !overflow, nextSequence <= protocolMaximum else { throw ProtocolError.invalidEvent }
        var next = state
        next.queue.append(event)
        next.capturePlan = planWithPoint(
            plan,
            point: point,
            nextSequence: nextSequence,
            stage: .followUp,
            nextCaptureAt: nextCaptureAt
        )
        try replace(next)
    }

    func rescheduleFollowUp(nextCaptureAt: Int64) throws {
        guard let plan = state.capturePlan, plan.stage == .followUp else {
            throw ProtocolError.invalidEvent
        }
        var next = state
        next.capturePlan = CapturePlan(
            incidentId: plan.incidentId,
            deviceId: plan.deviceId,
            keyVersion: plan.keyVersion,
            expiresAt: plan.expiresAt,
            nextSequence: plan.nextSequence,
            stage: plan.stage,
            startedAt: plan.startedAt,
            nextCaptureAt: nextCaptureAt,
            lastLatitudeE7: plan.lastLatitudeE7,
            lastLongitudeE7: plan.lastLongitudeE7,
            lastQuality: plan.lastQuality
        )
        try replace(next)
    }

    func archiveVerifiedTerminalIncident(incidentId: String) throws -> Bool {
        let matchingPlan = state.capturePlan?.incidentId == incidentId
        let matchingEvents = state.queue.contains { $0.incidentId == incidentId }
        guard matchingPlan || matchingEvents else { return false }
        var next = state
        next.queue.removeAll { $0.incidentId == incidentId }
        if matchingPlan { next.capturePlan = nil }
        try replace(next)
        return true
    }

    func scrubExpiredLocation(now: Int64) throws -> Bool {
        guard
            let plan = state.capturePlan,
            now >= plan.expiresAt,
            plan.lastLatitudeE7 != nil || plan.lastLongitudeE7 != nil || plan.lastQuality != 0
        else {
            return false
        }
        var next = state
        next.capturePlan = CapturePlan(
            incidentId: plan.incidentId,
            deviceId: plan.deviceId,
            keyVersion: plan.keyVersion,
            expiresAt: plan.expiresAt,
            nextSequence: plan.nextSequence,
            stage: plan.stage,
            startedAt: plan.startedAt,
            nextCaptureAt: plan.nextCaptureAt,
            lastLatitudeE7: nil,
            lastLongitudeE7: nil,
            lastQuality: 0
        )
        try replace(next)
        return true
    }

    func hasExpiredPending(now: Int64) -> Bool {
        pendingExpiry().map { now >= $0 } ?? false
    }

    @discardableResult
    func archiveExpired(now: Int64) throws -> ArchivedIncident {
        guard let expiresAt = pendingExpiry(), now >= expiresAt else {
            throw ProtocolError.invalidEvent
        }
        let incidentId = state.queue.first?.incidentId ?? state.capturePlan!.incidentId
        let archive = ArchivedIncident(
            incidentId: incidentId,
            expiresAt: expiresAt,
            archivedAt: now,
            result: "result_unknown"
        )
        try replace(StoredState(lastArchive: archive))
        return archive
    }

    func removeMatchingHead(eventId: String) throws -> Bool {
        guard state.queue.first?.eventId == eventId else { return false }
        var next = state
        next.queue.removeFirst()
        try replace(next)
        return true
    }

    private func requireMatches(_ event: IncidentEvent, plan: CapturePlan) throws {
        guard
            state.queue.count < Self.maximumEvents,
            event.kind == "location.updated",
            event.incidentId == plan.incidentId,
            event.deviceId == plan.deviceId,
            event.payload.keyVersion == plan.keyVersion,
            event.expiresAt == plan.expiresAt,
            event.sequence == plan.nextSequence
        else {
            throw ProtocolError.invalidEvent
        }
    }

    private func planWithPoint(
        _ plan: CapturePlan,
        point: LocationPoint,
        nextSequence: Int64,
        stage: CaptureStage,
        nextCaptureAt: Int64
    ) -> CapturePlan {
        let keepPrevious = point.quality == 0
        return CapturePlan(
            incidentId: plan.incidentId,
            deviceId: plan.deviceId,
            keyVersion: plan.keyVersion,
            expiresAt: plan.expiresAt,
            nextSequence: nextSequence,
            stage: stage,
            startedAt: plan.startedAt,
            nextCaptureAt: nextCaptureAt,
            lastLatitudeE7: keepPrevious ? plan.lastLatitudeE7 : point.latitudeE7,
            lastLongitudeE7: keepPrevious ? plan.lastLongitudeE7 : point.longitudeE7,
            lastQuality: keepPrevious ? plan.lastQuality : point.quality
        )
    }

    private func pendingExpiry() -> Int64? {
        state.queue.first?.expiresAt ?? state.capturePlan?.expiresAt
    }

    private func replace(_ next: StoredState) throws {
        try validate(next)
        let data = try encoder.encode(next)
        guard data.count <= Self.maximumFileBytes else { throw ProtocolError.invalidEvent }
        try data.write(to: fileURL, options: .atomic)
        state = next
    }

    private func validate(_ value: StoredState) throws {
        guard value.queue.count <= Self.maximumEvents else { throw ProtocolError.invalidEvent }
        try value.queue.forEach(Protocol.validateStored)
        if let first = value.queue.first {
            guard value.queue.allSatisfy({
                $0.incidentId == first.incidentId && $0.expiresAt == first.expiresAt
            }) else {
                throw ProtocolError.invalidEvent
            }
        }
        if let plan = value.capturePlan {
            try Protocol.validateId(plan.incidentId)
            try Protocol.validateId(plan.deviceId)
            guard
                (1...protocolMaximum).contains(plan.keyVersion),
                (0...protocolMaximum).contains(plan.expiresAt),
                (1...protocolMaximum).contains(plan.nextSequence),
                (0...plan.expiresAt).contains(plan.startedAt),
                (1...maximumEventLifetimeSeconds).contains(plan.expiresAt - plan.startedAt),
                (0...protocolMaximum).contains(plan.nextCaptureAt),
                plan.lastQuality <= 4,
                (plan.lastLatitudeE7 == nil) == (plan.lastLongitudeE7 == nil),
                (plan.lastQuality == 0) == (plan.lastLatitudeE7 == nil),
                value.queue.allSatisfy({
                    $0.incidentId == plan.incidentId && $0.expiresAt == plan.expiresAt
                })
            else {
                throw ProtocolError.invalidEvent
            }
        }
        if let archive = value.lastArchive {
            try Protocol.validateId(archive.incidentId)
            guard
                (1...protocolMaximum).contains(archive.expiresAt),
                (archive.expiresAt...protocolMaximum).contains(archive.archivedAt),
                archive.result == "result_unknown"
            else {
                throw ProtocolError.invalidEvent
            }
        }
    }

    private static func read(from url: URL) throws -> StoredState {
        guard FileManager.default.fileExists(atPath: url.path) else { return StoredState() }
        let attributes = try FileManager.default.attributesOfItem(atPath: url.path)
        guard
            let size = attributes[.size] as? NSNumber,
            size.intValue > 0,
            size.intValue <= maximumFileBytes
        else {
            throw ProtocolError.invalidEvent
        }
        let data = try Data(contentsOf: url, options: .mappedIfSafe)
        return try JSONDecoder().decode(StoredState.self, from: data)
    }

    private static let maximumEvents = 64
    private static let maximumFileBytes = 65_536
}
