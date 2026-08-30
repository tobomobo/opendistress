// SPDX-License-Identifier: MIT
import CoreLocation
import Combine
import Foundation
import WatchKit

func foregroundCadenceSeconds(startedAt: Int64, now: Int64, lowBattery: Bool) -> Int64 {
    let elapsed = max(0, now - startedAt)
    let base: Int64
    if elapsed < 5 * 60 {
        base = 30
    } else if elapsed < 30 * 60 {
        base = 2 * 60
    } else {
        base = 5 * 60
    }
    return lowBattery ? base * 2 : base
}

func nextForegroundCaptureAt(startedAt: Int64, now: Int64, lowBattery: Bool) -> Int64 {
    min(protocolMaximum, now + foregroundCadenceSeconds(
        startedAt: startedAt,
        now: now,
        lowBattery: lowBattery
    ))
}

func canCaptureLocation(now: Int64, expiresAt: Int64) -> Bool {
    now < expiresAt
}

func isMaterialLocation(plan: CapturePlan, point: LocationPoint) -> Bool {
    guard point.quality > 0 else { return false }
    if point.quality > plan.lastQuality { return true }
    guard
        let latitude = point.latitudeE7,
        let longitude = point.longitudeE7,
        let lastLatitude = plan.lastLatitudeE7,
        let lastLongitude = plan.lastLongitudeE7
    else {
        return true
    }
    let latitude1 = Double(lastLatitude) / 10_000_000 * .pi / 180
    let latitude2 = Double(latitude) / 10_000_000 * .pi / 180
    let deltaLatitude = latitude2 - latitude1
    let deltaLongitude = (Double(longitude) - Double(lastLongitude)) / 10_000_000 * .pi / 180
    let a = min(1, max(0,
        sin(deltaLatitude / 2) * sin(deltaLatitude / 2) +
            cos(latitude1) * cos(latitude2) *
            sin(deltaLongitude / 2) * sin(deltaLongitude / 2)
    ))
    let meters = 2 * 6_371_000 * atan2(sqrt(a), sqrt(1 - a))
    return meters >= 50
}

@MainActor
final class PanicController: NSObject, ObservableObject, CLLocationManagerDelegate {
    @Published private(set) var status = "Starting — no alert sent"
    @Published private(set) var buttonEnabled = true
    @Published private(set) var buttonTitle = "SEND / RETRY ALERT"

    private let config: RuntimeConfig?
    private let store: EventStore?
    private let transport: Transport?
    private var locationManager: CLLocationManager?
    private var pendingLocationPlan: CapturePlan?
    private var captureInProgress = false
    private var isSceneActive = false
    private var sending = false
    private var statusInProgress = false
    private var pendingStatusRequestId: String?
    private var retryTask: Task<Void, Never>?
    private var freshTimeout: Task<Void, Never>?
    private var followUpTask: Task<Void, Never>?
    private var statusTask: Task<Void, Never>?

    override init() {
        let loaded: (RuntimeConfig, EventStore, Transport)?
        do {
            let runtimeConfig = try RuntimeConfig()
            let loadedStore = try EventStore()
            try loadedStore.scrubExpiredLocation(
                now: Int64(Date().timeIntervalSince1970.rounded(.towardZero))
            )
            loaded = (runtimeConfig, loadedStore, Transport(config: runtimeConfig))
        } catch {
            loaded = nil
        }
        config = loaded?.0
        store = loaded?.1
        transport = loaded?.2
        super.init()
        guard let store else {
            status = "Not configured or stored data unreadable — no alert sent"
            buttonEnabled = false
            return
        }
        if store.state.queue.isEmpty, store.state.capturePlan == nil {
            status = "Ready — no alert sent"
        } else if store.state.queue.isEmpty, store.state.capturePlan?.stage == .followUp {
            status = "Incident active — foreground location follow-up scheduled"
        } else {
            status = "Stored on watch — relay acceptance pending"
        }
        refreshAction()
        drainQueue()
        continueCapture()
    }

    deinit {
        retryTask?.cancel()
        freshTimeout?.cancel()
        followUpTask?.cancel()
        statusTask?.cancel()
    }

    func setSceneActive(_ active: Bool) {
        guard active != isSceneActive else { return }
        isSceneActive = active
        if active {
            drainQueue()
            continueCapture()
        } else {
            followUpTask?.cancel()
            cancelStatusPoll()
            invalidateLocationRequest()
            WKInterfaceDevice.current().isBatteryMonitoringEnabled = false
        }
    }

    func activateOrRetry() {
        guard let config, let store else { return }
        let now = nowSeconds()
        do {
            try store.scrubExpiredLocation(now: now)
        } catch {
            status = "Expired location could not be scrubbed; no new alert sent"
            return
        }
        if store.hasExpiredPending(now: now) {
            do {
                try store.archiveExpired(now: now)
                followUpTask?.cancel()
                cancelStatusPoll()
                invalidateLocationRequest()
                retryTask?.cancel()
                retryTask = nil
                status = "Expired incident archived — result unknown; no new alert sent"
                WKInterfaceDevice.current().play(.failure)
                refreshAction()
            } catch {
                status = "Expired incident could not be archived — result unknown"
            }
            return
        }
        if !store.state.queue.isEmpty || store.state.capturePlan != nil {
            status = "Retrying stored alert — relay acceptance pending"
            drainQueue()
            continueCapture()
            return
        }
        do {
            let live = try Protocol.createLive(config: config, now: now)
            try store.startIncident(live)
            WKInterfaceDevice.current().play(.notification)
            status = "Recognized on this watch — relay acceptance pending"
            drainQueue()
            continueCapture()
        } catch {
            status = "Watch could not persist alert — no network send attempted"
            WKInterfaceDevice.current().play(.failure)
        }
    }

    private func continueCapture(statusChecked: Bool = false) {
        guard
            isSceneActive,
            !captureInProgress,
            let config,
            let store,
            let plan = store.state.capturePlan
        else {
            return
        }
        guard plan.deviceId == config.deviceId, plan.keyVersion == config.keyVersion else {
            status = "Local key configuration changed — location not queued"
            return
        }
        let now = nowSeconds()
        guard canCaptureLocation(now: now, expiresAt: plan.expiresAt) else {
            do {
                try store.scrubExpiredLocation(now: now)
            } catch {
                status = "Incident expired, but stored location could not be scrubbed"
                return
            }
            status = "Incident expired — ARCHIVE EXPIRED to record result unknown"
            refreshAction()
            return
        }
        if plan.stage == .followUp, now < plan.nextCaptureAt {
            scheduleLocationTick(after: min(plan.nextCaptureAt, plan.expiresAt) - now)
            return
        }
        if plan.stage == .followUp, !statusChecked {
            if sending {
                scheduleLocationTick(after: 1)
            } else {
                pollStatus(plan)
            }
            return
        }
        let manager = managerAfterActivation()
        switch manager.authorizationStatus {
        case .notDetermined:
            manager.requestWhenInUseAuthorization()
            status = "Alert stored — location permission requested after activation"
        case .authorizedAlways, .authorizedWhenInUse:
            capture(plan, manager: manager)
        case .denied, .restricted:
            unavailableLocation(plan, now: now)
        @unknown default:
            unavailableLocation(plan, now: now)
        }
    }

    private func managerAfterActivation() -> CLLocationManager {
        if let locationManager { return locationManager }
        let manager = CLLocationManager()
        manager.delegate = self
        locationManager = manager
        return manager
    }

    private func capture(_ plan: CapturePlan, manager: CLLocationManager) {
        captureInProgress = true
        if plan.stage == .snapshot {
            captureInProgress = false
            if queueLocation(expectedPlan: plan, location: manager.location) {
                continueCapture()
            }
            return
        }
        manager.desiredAccuracy = kCLLocationAccuracyBest
        pendingLocationPlan = plan
        manager.requestLocation()
        freshTimeout?.cancel()
        freshTimeout = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 15_000_000_000)
            guard
                !Task.isCancelled,
                let self,
                self.isSceneActive,
                self.captureInProgress,
                self.pendingLocationPlan == plan
            else {
                return
            }
            self.invalidateLocationRequest()
            if self.queueLocation(expectedPlan: plan, location: nil) {
                self.continueCapture()
            }
        }
    }

    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor [weak self] in self?.continueCapture() }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        Task { @MainActor [weak self] in
            guard
                let self,
                self.isSceneActive,
                self.captureInProgress,
                let activeManager = self.locationManager,
                activeManager === manager,
                let plan = self.pendingLocationPlan,
                self.store?.state.capturePlan == plan
            else {
                return
            }
            self.invalidateLocationRequest()
            if self.queueLocation(expectedPlan: plan, location: locations.last) {
                self.continueCapture()
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor [weak self] in
            guard
                let self,
                self.isSceneActive,
                self.captureInProgress,
                let activeManager = self.locationManager,
                activeManager === manager,
                let plan = self.pendingLocationPlan,
                self.store?.state.capturePlan == plan
            else {
                return
            }
            self.invalidateLocationRequest()
            if self.queueLocation(expectedPlan: plan, location: nil) {
                self.continueCapture()
            }
        }
    }

    @discardableResult
    private func queueLocation(expectedPlan: CapturePlan, location: CLLocation?) -> Bool {
        guard
            let config,
            let store,
            let plan = store.state.capturePlan,
            plan == expectedPlan
        else {
            return false
        }
        let createdAt = nowSeconds()
        guard canCaptureLocation(now: createdAt, expiresAt: plan.expiresAt) else {
            do {
                try store.scrubExpiredLocation(now: createdAt)
            } catch {
                status = "Incident expired, but stored location could not be scrubbed"
                return false
            }
            status = "Incident expired — ARCHIVE EXPIRED to record result unknown"
            refreshAction()
            return false
        }
        do {
            let sample = locationSample(location, stage: plan.stage, createdAt: createdAt)
            let point = try Protocol.locationPoint(sample)
            let nextCaptureAt = nextCaptureAt(plan: plan, now: createdAt)
            if plan.stage == .followUp, !isMaterialLocation(plan: plan, point: point) {
                try store.rescheduleFollowUp(nextCaptureAt: nextCaptureAt)
                status = "Location unchanged; foreground follow-up rescheduled"
                return true
            }
            let event = try Protocol.createLocation(
                config: config,
                incidentId: plan.incidentId,
                sequence: plan.nextSequence,
                createdAt: createdAt,
                expiresAt: plan.expiresAt,
                sample: sample
            )
            if plan.stage == .snapshot {
                try store.appendSnapshot(event, point: point)
                status = "Cached location encrypted and queued after live alert"
            } else if plan.stage == .fresh {
                try store.appendFresh(event, point: point, nextCaptureAt: nextCaptureAt)
                status = "Fresh location attempt encrypted and queued after live alert"
            } else {
                try store.appendFollowUp(event, point: point, nextCaptureAt: nextCaptureAt)
                status = "Material location update encrypted and queued"
            }
            drainQueue()
            return true
        } catch {
            status = "Location could not be persisted; live alert remains queued"
            WKInterfaceDevice.current().play(.failure)
            return false
        }
    }

    private func locationSample(
        _ location: CLLocation?,
        stage: CaptureStage,
        createdAt: Int64
    ) -> LocationSample {
        let path: UInt8 = stage == .snapshot ? 0 : 1
        guard
            let location,
            location.coordinate.latitude.isFinite,
            location.coordinate.longitude.isFinite,
            (-90.0...90.0).contains(location.coordinate.latitude),
            (-180.0...180.0).contains(location.coordinate.longitude)
        else {
            return LocationSample(captureAt: 0, latitude: nil, longitude: nil, quality: 0, path: path)
        }
        let timestamp = location.timestamp.timeIntervalSince1970
        guard timestamp > 0, timestamp <= Double(UInt32.max) else {
            return LocationSample(captureAt: 0, latitude: nil, longitude: nil, quality: 0, path: path)
        }
        let captureAt = UInt64(timestamp.rounded(.towardZero))
        guard createdAt >= 0, captureAt <= UInt64(createdAt) else {
            return LocationSample(captureAt: 0, latitude: nil, longitude: nil, quality: 0, path: path)
        }
        let quality: UInt8
        if stage == .snapshot {
            quality = 1
        } else if location.horizontalAccuracy < 0 {
            quality = 2
        } else if location.horizontalAccuracy <= 20 {
            quality = 4
        } else if location.horizontalAccuracy <= 100 {
            quality = 3
        } else {
            quality = 2
        }
        return LocationSample(
            captureAt: captureAt,
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            quality: quality,
            path: path
        )
    }

    private func queueUnavailableLocations() {
        while let plan = store?.state.capturePlan {
            if plan.stage == .followUp {
                continueCapture()
                return
            }
            queueLocation(expectedPlan: plan, location: nil)
            guard store?.state.capturePlan != plan else { return }
        }
    }

    private func drainQueue() {
        guard !sending, !statusInProgress, let store, let transport else { return }
        guard let event = store.state.queue.first else { return }
        let now = nowSeconds()
        if now >= event.expiresAt {
            do {
                try store.scrubExpiredLocation(now: now)
            } catch {
                status = "Stored event expired, but location could not be scrubbed"
                return
            }
        }
        guard now < event.expiresAt else {
            status = "Stored event expired — ARCHIVE EXPIRED to record result unknown"
            refreshAction()
            return
        }
        sending = true
        Task { [weak self] in
            let outcome = await transport.send(event)
            guard let self else { return }
            self.sending = false
            guard store.state.queue.first?.eventId == event.eventId else { return }
            do {
                if outcome.accepted, try store.removeMatchingHead(eventId: event.eventId) {
                    self.status = outcome.label
                    WKInterfaceDevice.current().play(.success)
                    self.refreshAction()
                    self.drainQueue()
                    self.continueCapture()
                } else {
                    self.status = outcome.label
                    self.scheduleRetry()
                }
            } catch {
                self.status = "Relay evidence verified, but the stored event could not be cleared"
                self.scheduleRetry()
            }
        }
    }

    private func pollStatus(_ expectedPlan: CapturePlan) {
        guard
            isSceneActive,
            !statusInProgress,
            let config,
            let store,
            let transport,
            store.state.capturePlan == expectedPlan
        else {
            return
        }
        let now = nowSeconds()
        guard canCaptureLocation(now: now, expiresAt: expectedPlan.expiresAt) else {
            continueCapture(statusChecked: true)
            return
        }
        let query: StatusQuery
        do {
            query = try Protocol.createStatusQuery(config: config, plan: expectedPlan, now: now)
        } catch {
            continueCapture(statusChecked: true)
            return
        }
        statusInProgress = true
        pendingStatusRequestId = query.requestId
        statusTask = Task { [weak self] in
            let outcome = await transport.sendStatus(query)
            guard
                !Task.isCancelled,
                let self,
                self.isSceneActive,
                self.pendingStatusRequestId == query.requestId
            else {
                return
            }
            self.statusInProgress = false
            self.pendingStatusRequestId = nil
            self.statusTask = nil
            guard store.state.capturePlan == expectedPlan else { return }
            let verified = outcome.verified
            if let verified, (verified.state == "resolved" || verified.state == "expired") {
                do {
                    if try store.archiveVerifiedTerminalIncident(incidentId: query.incidentId) {
                        self.followUpTask?.cancel()
                        self.invalidateLocationRequest()
                        self.retryTask?.cancel()
                        self.retryTask = nil
                        self.status =
                            "Verified incident \(verified.state) — queued retransmissions archived"
                        WKInterfaceDevice.current().play(.click)
                        self.refreshAction()
                    }
                } catch {
                    self.status = "Verified incident status could not be persisted; location continues"
                    self.drainQueue()
                    self.continueCapture(statusChecked: true)
                }
                return
            }
            if verified?.state == "acknowledged" {
                self.status = "Human acknowledgement verified — incident remains active"
            }
            self.drainQueue()
            self.continueCapture(statusChecked: true)
        }
    }

    private func scheduleRetry() {
        retryTask?.cancel()
        retryTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: 30_000_000_000)
            guard !Task.isCancelled else { return }
            self?.drainQueue()
        }
    }

    private func unavailableLocation(_ plan: CapturePlan, now: Int64) {
        if plan.stage == .followUp {
            do {
                try store?.rescheduleFollowUp(nextCaptureAt: nextCaptureAt(plan: plan, now: now))
                status = "Foreground location unavailable; next attempt scheduled"
                continueCapture()
            } catch {
                status = "Location schedule could not be persisted"
            }
        } else {
            status = "Location unavailable after activation; encrypted unavailable records queued"
            queueUnavailableLocations()
        }
    }

    private func nextCaptureAt(plan: CapturePlan, now: Int64) -> Int64 {
        let device = WKInterfaceDevice.current()
        device.isBatteryMonitoringEnabled = true
        let level = device.batteryLevel
        return nextForegroundCaptureAt(
            startedAt: plan.startedAt,
            now: now,
            lowBattery: level >= 0 && level <= 0.2
        )
    }

    private func scheduleLocationTick(after seconds: Int64) {
        followUpTask?.cancel()
        followUpTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: UInt64(max(1, seconds)) * 1_000_000_000)
            guard !Task.isCancelled else { return }
            self?.continueCapture()
        }
    }

    private func invalidateLocationRequest() {
        freshTimeout?.cancel()
        freshTimeout = nil
        locationManager?.delegate = nil
        locationManager = nil
        pendingLocationPlan = nil
        captureInProgress = false
    }

    private func cancelStatusPoll() {
        statusTask?.cancel()
        statusTask = nil
        statusInProgress = false
        pendingStatusRequestId = nil
    }

    private func refreshAction() {
        buttonTitle = store?.hasExpiredPending(now: nowSeconds()) == true
            ? "ARCHIVE EXPIRED — RESULT UNKNOWN"
            : "SEND / RETRY ALERT"
    }

    private func nowSeconds() -> Int64 {
        Int64(Date().timeIntervalSince1970.rounded(.towardZero))
    }
}
