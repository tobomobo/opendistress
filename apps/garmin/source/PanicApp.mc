// SPDX-License-Identifier: MIT

import Toybox.Application;
import Toybox.Application.Properties;
import Toybox.Application.Storage;
import Toybox.Communications;
import Toybox.Complications;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Position;
import Toybox.System;
import Toybox.Time;
import Toybox.Timer;
import Toybox.WatchUi;

class PanicApp extends Application.AppBase {
    function initialize() {
        AppBase.initialize();
        try {
            Complications.updateComplication(0, {
                :value => "OPEN",
                :shortLabel => "PANIC"
            });
        } catch (error) {
            // A face may not have selected the published complication yet.
        }
    }

    function getInitialView() {
        var view = new PanicView();
        return [view, new PanicDelegate(view)];
    }

    (:glance)
    function getGlanceView() {
        return [new PanicGlanceView()];
    }
}

(:glance)
class PanicGlanceView extends WatchUi.GlanceView {
    function initialize() {
        GlanceView.initialize();
    }

    function onUpdate(dc) {
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.drawText(
            dc.getWidth() / 2,
            dc.getHeight() / 2,
            Graphics.FONT_SMALL,
            "OPEN PANIC APP",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER
        );
    }
}

class PanicView extends WatchUi.View {
    const STATE_KEY = "event_state_v2";
    const LEGACY_PENDING_KEY = "pending_event";
    const LIVE_EXPIRY_SECONDS = 3600;
    const MAX_QUEUE = 3;
    const MAX_INITIAL_RETRIES = 2;
    const RETRY_DELAY_MS = 5000;
    const MATERIAL_MOVE_E7 = 5000;
    const LOW_BATTERY_PERCENT = 20;
    const FIRST_CADENCE_SECONDS = 30;
    const MIDDLE_CADENCE_SECONDS = 120;
    const LATE_CADENCE_SECONDS = 300;
    const STATE_KEYS = ["queue", "active"];
    const ACTIVE_KEYS = [
        "incident_id",
        "expires_at",
        "next_sequence",
        "last_location_hex",
        "last_location_queued_at",
        "capture_stage"
    ];

    var _state = "READY — TEST";
    var _detail = "MENU arms LIVE; START sends";
    var _queue = [];
    var _activeIncident = null;
    var _displayEventId = null;
    var _mode = "TEST";
    var _inFlight = false;
    var _activeKeyHex = null;
    var _requestEventId = null;
    var _statusQuery = null;
    var _retryCount = 0;
    var _retryTimer;
    var _locationExpiryTimer;
    var _statusTimer;
    var _visible = false;

    function initialize() {
        View.initialize();
        _retryTimer = new Timer.Timer();
        _locationExpiryTimer = new Timer.Timer();
        _statusTimer = new Timer.Timer();
        loadState();
    }

    function onShow() {
        _visible = true;
        resumeLocations();
    }

    function onHide() {
        _visible = false;
        stopLocations();
        try {
            _retryTimer.stop();
        } catch (error) {
        }
    }

    function loadState() {
        try {
            var stored = Storage.getValue(STATE_KEY);
            if (stored == null) {
                migrateLegacyTest();
                return;
            }
            if (!validStoredState(stored)) {
                setState("CONFIGURATION FAILURE", "Stored event state is invalid");
                return;
            }
            _queue = stored["queue"];
            _activeIncident = stored["active"];
            if (_queue.size() > 0) {
                _displayEventId = _queue[0]["event_id"];
                setState("PENDING", "START retries immutable event");
            } else if (_activeIncident != null) {
                _displayEventId = _activeIncident["incident_id"];
                setState("INCIDENT ACTIVE", "No event is waiting for relay");
            }
        } catch (error) {
            setState("CONFIGURATION FAILURE", "Cannot read persistent storage");
        }
    }

    function migrateLegacyTest() {
        var legacy = Storage.getValue(LEGACY_PENDING_KEY);
        if (legacy == null) {
            return;
        }
        if (!PanicProtocol.isTestEvent(legacy)) {
            setState("CONFIGURATION FAILURE", "Stored legacy TEST is invalid");
            return;
        }
        var migrated = [legacy];
        Storage.setValue(STATE_KEY, {"queue" => migrated, "active" => null});
        Storage.deleteValue(LEGACY_PENDING_KEY);
        _queue = migrated;
        _displayEventId = legacy["event_id"];
        setState("PENDING", "Legacy TEST migrated; START retries");
    }

    function validStoredState(value) {
        if (!PanicProtocol.hasExactKeys(value, STATE_KEYS)
            || !(value["queue"] instanceof Lang.Array)
            || value["queue"].size() > MAX_QUEUE
            || !validActive(value["active"])) {
            return false;
        }
        var queue = value["queue"];
        var archivedIncidentId = "";
        var archivedExpiresAt = -1;
        for (var i = 0; i < queue.size(); i += 1) {
            if (!PanicProtocol.isEvent(queue[i])) {
                return false;
            }
            if (queue[i]["v"] == 2) {
                if (value["active"] != null) {
                    if (queue[i]["incident_id"] != value["active"]["incident_id"]
                        || queue[i]["expires_at"] != value["active"]["expires_at"]) {
                        return false;
                    }
                } else if (archivedIncidentId.length() == 0) {
                    archivedIncidentId = queue[i]["incident_id"];
                    archivedExpiresAt = queue[i]["expires_at"];
                } else if (queue[i]["incident_id"] != archivedIncidentId
                    || queue[i]["expires_at"] != archivedExpiresAt) {
                    return false;
                }
            }
        }
        return true;
    }

    function validActive(value) {
        return value == null
            || (PanicProtocol.hasExactKeys(value, ACTIVE_KEYS)
                && PanicProtocol.isCanonicalId(value["incident_id"])
                && value["expires_at"] instanceof Lang.Number
                && value["expires_at"] >= 0
                && value["next_sequence"] instanceof Lang.Number
                && value["next_sequence"] >= 1
                && value["last_location_hex"] instanceof Lang.String
                && (value["last_location_hex"].length() == 0
                    || PanicProtocol.isLowerHex(value["last_location_hex"], 32))
                && value["last_location_queued_at"] instanceof Lang.Number
                && value["last_location_queued_at"] >= 0
                && value["last_location_queued_at"] <= value["expires_at"]
                && value["capture_stage"] instanceof Lang.Number
                && value["capture_stage"] >= 0
                && value["capture_stage"] <= 2
                && (value["capture_stage"] == 0
                    ? value["last_location_hex"].length() == 0
                        && value["last_location_queued_at"] == 0
                    : value["last_location_hex"].length() == 32));
    }

    function onUpdate(dc) {
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
        dc.clear();
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        var centerX = dc.getWidth() / 2;
        var centerY = dc.getHeight() / 2;
        dc.drawText(
            centerX,
            centerY - 50,
            Graphics.FONT_SMALL,
            _state,
            Graphics.TEXT_JUSTIFY_CENTER
        );
        dc.drawText(
            centerX,
            centerY - 2,
            Graphics.FONT_XTINY,
            _detail,
            Graphics.TEXT_JUSTIFY_CENTER
        );
        if (_displayEventId != null) {
            dc.drawText(
                centerX,
                centerY + 36,
                Graphics.FONT_XTINY,
                _displayEventId,
                Graphics.TEXT_JUSTIFY_CENTER
            );
        }
    }

    function activate() {
        if (_inFlight) {
            return;
        }
        if (_queue.size() > 0) {
            sendPending();
            return;
        }
        if (_activeIncident != null) {
            var now = currentTime();
            if (now == null) {
                return;
            }
            if (now < _activeIncident["expires_at"]) {
                _displayEventId = _activeIncident["incident_id"];
                setState("INCIDENT ACTIVE", "Repeated START keeps the same incident");
                return;
            }
            expireLocations();
            return;
        }
        if (_mode == "LIVE") {
            activateLive();
        } else {
            activateTest();
        }
    }

    function activateTest() {
        var baseUrl = Properties.getValue("relayBaseUrl");
        var deviceId = Properties.getValue("deviceId");
        var keyHex = Properties.getValue("hmacKeyHex");
        if (!PanicProtocol.isHttpsBaseUrl(baseUrl)
            || !PanicProtocol.isCanonicalId(deviceId)
            || !PanicProtocol.isSafeAuthKey(keyHex)) {
            setState("CONFIGURATION FAILURE", "Check TEST URL, device ID, and key");
            return;
        }
        var now = currentTime();
        if (now == null) {
            return;
        }
        var event = PanicProtocol.newTestEvent(PanicProtocol.randomId(), deviceId, now);
        if (!PanicProtocol.isTestEvent(event)
            || !persistState([event], _activeIncident)) {
            setState("CONFIGURATION FAILURE", "Cannot persist TEST event");
            return;
        }
        _displayEventId = event["event_id"];
        _retryCount = 0;
        sendPending();
    }

    function activateLive() {
        var now = currentTime();
        if (now == null) {
            return;
        }
        if (_activeIncident != null && now < _activeIncident["expires_at"]) {
            _displayEventId = _activeIncident["incident_id"];
            setState("INCIDENT ACTIVE", "Repeated START keeps the same incident");
            return;
        }
        var config = liveConfiguration();
        if (config == null) {
            return;
        }
        if (now > PanicProtocol.MAX_TIME - LIVE_EXPIRY_SECONDS) {
            setState("CONFIGURATION FAILURE", "Watch time is outside v2 range");
            return;
        }
        var incidentId = PanicProtocol.randomId();
        var event;
        try {
            event = PanicProtocol.newEncryptedEvent(
                PanicProtocol.V2_LIVE_KIND,
                incidentId,
                incidentId,
                config["device_id"],
                0,
                now,
                now + LIVE_EXPIRY_SECONDS,
                config["key_version"],
                PanicProtocol.hexBytes(config["template_id"]),
                config["enc_key"],
                config["mac_key"]
            );
        } catch (error) {
            setState("CONFIGURATION FAILURE", "Cannot encrypt LIVE trigger");
            return;
        }
        var active = {
            "incident_id" => incidentId,
            "expires_at" => event["expires_at"],
            "next_sequence" => 1,
            "last_location_hex" => "",
            "last_location_queued_at" => 0,
            "capture_stage" => 0
        };
        if (!PanicProtocol.isEncryptedEvent(event) || !persistState([event], active)) {
            setState("CONFIGURATION FAILURE", "Cannot persist LIVE trigger");
            return;
        }
        _displayEventId = incidentId;
        _retryCount = 0;

        // The alert is durable and its network submission is started before GPS.
        sendPending();
        captureLocations();
    }

    function captureLocations() {
        if (_activeIncident == null) {
            return;
        }
        var now = currentTime();
        if (now == null) {
            stopLocations();
            return;
        }
        if (now >= _activeIncident["expires_at"]) {
            expireLocations();
            return;
        }
        scheduleLocationExpiry(now);
        scheduleStatusPoll(now);
        if (_activeIncident["capture_stage"] == 0) {
            var snapshot as Position.Info or Null = null;
            try {
                snapshot = Position.getInfo();
            } catch (error) {
            }
            if (!appendLocation(snapshot, 0, 1)) {
                return;
            }
        }
        if (_activeIncident != null && _activeIncident["capture_stage"] == 1) {
            try {
                Position.enableLocationEvents(Position.LOCATION_CONTINUOUS, method(:onPosition));
            } catch (error) {
                if (appendLocation(null, 1, 2)) {
                    setState("LOCATION UNAVAILABLE", "Encrypted unavailable fix queued");
                    startContinuousLocations();
                } else {
                    setState("LOCATION UNAVAILABLE", "Fresh fix remains pending");
                }
            }
        } else if (_activeIncident != null && _activeIncident["capture_stage"] == 2) {
            startContinuousLocations();
        }
    }

    function resumeLocations() {
        if (_activeIncident == null) {
            return;
        }
        var now = currentTime();
        if (now == null) {
            return;
        }
        if (now >= _activeIncident["expires_at"]) {
            expireLocations();
            return;
        }
        captureLocations();
    }

    function startContinuousLocations() {
        if (!_visible
            || _activeIncident == null
            || _activeIncident["capture_stage"] != 2) {
            return;
        }
        var now = currentTime();
        if (now == null) {
            return;
        }
        if (now >= _activeIncident["expires_at"]) {
            expireLocations();
            return;
        }
        try {
            Position.enableLocationEvents(Position.LOCATION_CONTINUOUS, method(:onPosition));
        } catch (error) {
            setState("LOCATION UNAVAILABLE", "Foreground cadence could not start");
        }
    }

    function stopLocations() {
        try {
            _locationExpiryTimer.stop();
        } catch (error) {
        }
        try {
            _statusTimer.stop();
        } catch (error) {
        }
        try {
            Position.enableLocationEvents(Position.LOCATION_DISABLE, null);
        } catch (error) {
        }
    }

    function expireLocations() {
        stopLocations();
        _mode = "TEST";
        if (_activeIncident != null && !persistState(_queue, null)) {
            setState("LOCAL DISARM UNSAVED", "Expired location state could not be scrubbed");
            return;
        }
        setState(_queue.size() > 0 ? "RESULT UNKNOWN — EXPIRED" : "INCIDENT EXPIRED",
            _queue.size() > 0
                ? "Encrypted pending events retained; MENU archives"
                : "START defaults to TEST");
    }

    function scheduleLocationExpiry(now) {
        try {
            _locationExpiryTimer.stop();
        } catch (error) {
        }
        var remaining = _activeIncident["expires_at"] - now;
        if (remaining < 0) {
            expireLocations();
            return;
        }
        var delaySeconds = remaining >= LIVE_EXPIRY_SECONDS
            ? LIVE_EXPIRY_SECONDS
            : remaining;
        try {
            _locationExpiryTimer.start(
                method(:expireLocations),
                delaySeconds * 1000,
                false
            );
        } catch (error) {
            setState("LOCATION UNAVAILABLE", "Expiry timer could not start");
        }
    }

    function onPosition(info) {
        if (!_visible || _activeIncident == null) {
            return;
        }
        var now = currentTime();
        if (now == null) {
            return;
        }
        if (now >= _activeIncident["expires_at"]) {
            expireLocations();
            return;
        }
        var queued = false;
        if (_activeIncident["capture_stage"] == 1) {
            queued = appendLocation(info, 1, 2);
        } else if (_activeIncident["capture_stage"] == 2
            && shouldQueueCadenceLocation(info, now)) {
            queued = appendLocation(info, 1, 2);
        }
        if (queued && !_inFlight && _queue.size() > 0) {
            sendPending();
        }
    }

    function shouldQueueCadenceLocation(info, now) {
        if (_queue.size() >= MAX_QUEUE
            || info == null
            || info.position == null
            || info.when == null
            || _activeIncident["last_location_hex"].length() == 0) {
            return false;
        }
        if (info.when.value() > now) {
            setState("CLOCK INCONSISTENT", "Future GPS fix was not queued");
            return false;
        }
        var record = PanicProtocol.locationRecord(info, 1);
        var previous = PanicProtocol.hexBytes(_activeIncident["last_location_hex"]);
        var captureAt = record.decodeNumber(Lang.NUMBER_FORMAT_UINT32, {
            :offset => 2,
            :endianness => Lang.ENDIAN_BIG
        });
        var previousCaptureAt = previous.decodeNumber(Lang.NUMBER_FORMAT_UINT32, {
            :offset => 2,
            :endianness => Lang.ENDIAN_BIG
        });
        if (captureAt == 0) {
            return false;
        }
        if (previousCaptureAt == 0 || record[14] > previous[14]) {
            return true;
        }
        var lastQueuedAt = _activeIncident["last_location_queued_at"];
        if (now < lastQueuedAt || now - lastQueuedAt < cadenceSeconds(now)) {
            return false;
        }
        var latitude = record.decodeNumber(Lang.NUMBER_FORMAT_SINT32, {
            :offset => 6,
            :endianness => Lang.ENDIAN_BIG
        });
        var previousLatitude = previous.decodeNumber(Lang.NUMBER_FORMAT_SINT32, {
            :offset => 6,
            :endianness => Lang.ENDIAN_BIG
        });
        var longitude = record.decodeNumber(Lang.NUMBER_FORMAT_SINT32, {
            :offset => 10,
            :endianness => Lang.ENDIAN_BIG
        });
        var previousLongitude = previous.decodeNumber(Lang.NUMBER_FORMAT_SINT32, {
            :offset => 10,
            :endianness => Lang.ENDIAN_BIG
        });
        return coordinateChanged(latitude, previousLatitude)
            || coordinateChanged(longitude, previousLongitude);
    }

    function coordinateChanged(current, previous) {
        return (current.toDouble() - previous.toDouble()).abs()
            > MATERIAL_MOVE_E7.toDouble();
    }

    function cadenceSeconds(now) {
        var expiresAt = _activeIncident["expires_at"];
        var startedAt = expiresAt >= LIVE_EXPIRY_SECONDS
            ? expiresAt - LIVE_EXPIRY_SECONDS
            : 0;
        var activeFor = now >= startedAt ? now - startedAt : 0;
        var seconds = activeFor < 300
            ? FIRST_CADENCE_SECONDS
            : (activeFor < 1800 ? MIDDLE_CADENCE_SECONDS : LATE_CADENCE_SECONDS);
        try {
            var stats = System.getSystemStats();
            if (!stats.charging && stats.battery <= LOW_BATTERY_PERCENT) {
                seconds *= 2;
            }
        } catch (error) {
        }
        return seconds;
    }

    function scheduleStatusPoll(now) {
        try {
            _statusTimer.stop();
        } catch (error) {
        }
        if (!_visible
            || _activeIncident == null
            || now >= _activeIncident["expires_at"]) {
            return;
        }
        var delaySeconds = cadenceSeconds(now);
        if (delaySeconds >= _activeIncident["expires_at"] - now) {
            return;
        }
        try {
            _statusTimer.start(method(:pollStatus), delaySeconds * 1000, false);
        } catch (error) {
            setState("STATUS UNKNOWN", "Foreground status timer could not start");
        }
    }

    function pollStatus() {
        if (!_visible || _activeIncident == null) {
            return;
        }
        var now = currentTime();
        if (now == null) {
            return;
        }
        if (now >= _activeIncident["expires_at"]) {
            expireLocations();
            return;
        }
        if (_inFlight) {
            scheduleStatusPoll(now);
            return;
        }
        if (!canPollStatus()) {
            sendPending();
            scheduleStatusPoll(now);
            return;
        }
        sendStatusQuery(now);
    }

    function canPollStatus() {
        if (_queue.size() == 0) {
            return true;
        }
        if (_activeIncident == null) {
            return false;
        }
        var head = _queue[0];
        return PanicProtocol.isEncryptedEvent(head)
            && head["kind"] == PanicProtocol.V2_LOCATION_KIND
            && head["incident_id"] == _activeIncident["incident_id"]
            && head["expires_at"] == _activeIncident["expires_at"];
    }

    function sendStatusQuery(now) {
        if (_inFlight
            || !_visible
            || !canPollStatus()
            || _activeIncident == null
            || now >= _activeIncident["expires_at"]) {
            return;
        }
        var config = liveConfiguration();
        if (config == null) {
            return;
        }
        var query = PanicProtocol.newStatusQuery(
            PanicProtocol.randomId(),
            _activeIncident["incident_id"],
            config["device_id"],
            now,
            _activeIncident["expires_at"]
        );
        if (!PanicProtocol.isStatusQuery(query)) {
            setState("CONFIGURATION FAILURE", "Cannot create incident status query");
            return;
        }
        var signature;
        try {
            signature = PanicProtocol.statusRequestSignature(config["auth_key"], query);
        } catch (error) {
            setState("CONFIGURATION FAILURE", "Cannot authenticate status query");
            return;
        }
        _activeKeyHex = config["auth_key"];
        _requestEventId = null;
        _statusQuery = query;
        _inFlight = true;
        _displayEventId = query["incident_id"];
        setState("CHECKING STATUS", connectionSummary());
        var options = {
            :method => Communications.HTTP_REQUEST_METHOD_POST,
            :headers => {
                "Content-Type" => Communications.REQUEST_CONTENT_TYPE_JSON,
                "X-SPB-Signature" => signature
            },
            :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON,
            :context => query["request_id"]
        };
        try {
            Communications.makeWebRequest(
                config["base_url"] + "/v2/status",
                query,
                options,
                method(:onStatusResponse)
            );
        } catch (error) {
            _inFlight = false;
            _activeKeyHex = null;
            _statusQuery = null;
            statusFailure("Status request could not be queued");
        }
    }

    function onStatusResponse(responseCode, data, requestId) {
        if (!_inFlight
            || _statusQuery == null
            || requestId != _statusQuery["request_id"]) {
            return;
        }
        var query = _statusQuery;
        var keyHex = _activeKeyHex;
        _inFlight = false;
        _activeKeyHex = null;
        _statusQuery = null;
        var receiveAt = currentTime();
        if (receiveAt == null) {
            setState("STATUS UNKNOWN", "Response retained no terminal meaning");
            return;
        }
        if (_activeIncident == null
            || _activeIncident["incident_id"] != query["incident_id"]
            || _activeIncident["expires_at"] != query["expires_at"]) {
            setState("STATUS UNKNOWN", "Active incident changed during request");
            return;
        }
        var verified = false;
        if (responseCode == 200) {
            try {
                verified = PanicProtocol.verifyStatusResult(data, query, keyHex, receiveAt);
            } catch (error) {
                verified = false;
            }
        }
        if (!verified) {
            if (receiveAt >= query["expires_at"]) {
                expireLocations();
            } else {
                statusFailure("Unsigned or mismatched status result");
            }
            return;
        }
        if (data["state"] == "resolved" || data["state"] == "expired") {
            finishIncidentFromStatus(query, data["state"]);
            return;
        }
        if (receiveAt >= query["expires_at"]) {
            expireLocations();
            return;
        }
        setState(data["state"] == "acknowledged"
            ? "RECIPIENT ACKNOWLEDGED"
            : "INCIDENT ACTIVE", "Verified relay status; acquisition continues");
        continueAfterStatus(receiveAt);
    }

    function statusFailure(detail) {
        setState("STATUS UNKNOWN", detail + "; LIVE remains active");
        var now = currentTime();
        if (now != null) {
            continueAfterStatus(now);
        }
    }

    function continueAfterStatus(now) {
        if (_activeIncident == null) {
            return;
        }
        if (now >= _activeIncident["expires_at"]) {
            expireLocations();
        } else if (_queue.size() > 0) {
            sendPending();
            scheduleStatusPoll(now);
        } else {
            scheduleStatusPoll(now);
        }
    }

    function finishIncidentFromStatus(query, state) {
        var remaining = [];
        for (var i = 0; i < _queue.size(); i += 1) {
            if (_queue[i]["v"] == 1
                || _queue[i]["incident_id"] != query["incident_id"]) {
                remaining.add(_queue[i]);
            }
        }
        stopLocations();
        _mode = "TEST";
        try {
            _retryTimer.stop();
        } catch (error) {
        }
        if (!persistState(remaining, null)) {
            setState("RESULT UNKNOWN", "Verified terminal status; local disarm was not saved");
            return;
        }
        _retryCount = 0;
        setState(state == "resolved" ? "INCIDENT RESOLVED" : "INCIDENT EXPIRED",
            "Signed relay status verified; START defaults to TEST");
    }

    function appendLocation(info, path, nextCaptureStage) {
        if (_activeIncident == null || _queue.size() >= MAX_QUEUE) {
            return false;
        }
        var now = currentTime();
        if (now == null || now >= _activeIncident["expires_at"]) {
            return false;
        }
        if (info != null && info.when != null && info.when.value() > now) {
            setState("CLOCK INCONSISTENT", "Future GPS fix was not queued");
            return false;
        }
        var record = PanicProtocol.locationRecord(info, path);
        var recordHex = PanicProtocol.bytesHex(record);
        if (recordHex == _activeIncident["last_location_hex"]) {
            return false;
        }
        var config = liveConfiguration();
        if (config == null) {
            return false;
        }
        var sequence = _activeIncident["next_sequence"];
        var event;
        try {
            event = PanicProtocol.newEncryptedEvent(
                PanicProtocol.V2_LOCATION_KIND,
                PanicProtocol.randomId(),
                _activeIncident["incident_id"],
                config["device_id"],
                sequence,
                now,
                _activeIncident["expires_at"],
                config["key_version"],
                record,
                config["enc_key"],
                config["mac_key"]
            );
        } catch (error) {
            setState("CONFIGURATION FAILURE", "Cannot encrypt location update");
            return false;
        }
        if (!PanicProtocol.isEncryptedEvent(event)) {
            setState("CONFIGURATION FAILURE", "Encrypted location event is invalid");
            return false;
        }
        var nextQueue = copyQueue(0);
        nextQueue.add(event);
        var nextActive = {
            "incident_id" => _activeIncident["incident_id"],
            "expires_at" => _activeIncident["expires_at"],
            "next_sequence" => sequence + 1,
            "last_location_hex" => recordHex,
            "last_location_queued_at" => now,
            "capture_stage" => nextCaptureStage
        };
        if (!persistState(nextQueue, nextActive)) {
            setState("CONFIGURATION FAILURE", "Cannot persist location update");
            return false;
        }
        return true;
    }

    function liveConfiguration() {
        var baseUrl = Properties.getValue("relayBaseUrl");
        var deviceId = Properties.getValue("deviceId");
        var authKey = Properties.getValue("liveAuthKeyHex");
        var encKey = Properties.getValue("liveEncKeyHex");
        var macKey = Properties.getValue("liveMacKeyHex");
        var templateId = Properties.getValue("liveTemplateIdHex");
        var keyVersion = Properties.getValue("liveKeyVersion");
        if (!PanicProtocol.isHttpsBaseUrl(baseUrl)
            || !PanicProtocol.isCanonicalId(deviceId)
            || !PanicProtocol.isSafeLiveConfiguration(
                authKey,
                encKey,
                macKey,
                templateId,
                keyVersion
            )) {
            setState("CONFIGURATION FAILURE", "LIVE build secrets are not provisioned");
            return null;
        }
        return {
            "base_url" => baseUrl,
            "device_id" => deviceId,
            "auth_key" => authKey,
            "enc_key" => encKey,
            "mac_key" => macKey,
            "template_id" => templateId,
            "key_version" => keyVersion
        };
    }

    function sendPending() {
        if (_inFlight || _queue.size() == 0) {
            return;
        }
        try {
            _retryTimer.stop();
        } catch (error) {
        }
        var event = _queue[0];
        var baseUrl = Properties.getValue("relayBaseUrl");
        var deviceId = Properties.getValue("deviceId");
        var keyHex = event["v"] == 1
            ? Properties.getValue("hmacKeyHex")
            : Properties.getValue("liveAuthKeyHex");
        var keyIsSafe = PanicProtocol.isSafeAuthKey(keyHex);
        if (!PanicProtocol.isEvent(event)) {
            setState("CONFIGURATION FAILURE", "Pending event is invalid");
            return;
        }
        var now = currentTime();
        if (now == null) {
            return;
        }
        if (now >= event["expires_at"]) {
            if (event["v"] == 2) {
                expireLocations();
            } else {
                setState("TEST EXPIRED", "MENU removes pending TEST");
            }
            return;
        }
        if (!PanicProtocol.isHttpsBaseUrl(baseUrl)
            || event["device_id"] != deviceId
            || !keyIsSafe) {
            setState("CONFIGURATION FAILURE", "Pending event does not match this build");
            return;
        }
        var signature;
        try {
            signature = PanicProtocol.requestSignature(keyHex, event);
        } catch (error) {
            setState("CONFIGURATION FAILURE", "Cannot authenticate pending event");
            return;
        }
        _activeKeyHex = keyHex;
        _requestEventId = event["event_id"];
        _inFlight = true;
        _displayEventId = event["event_id"];
        setState("SENDING", connectionSummary());
        var options = {
            :method => Communications.HTTP_REQUEST_METHOD_POST,
            :headers => {
                "Content-Type" => Communications.REQUEST_CONTENT_TYPE_JSON,
                "X-SPB-Signature" => signature
            },
            :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON,
            :context => event["event_id"]
        };
        try {
            Communications.makeWebRequest(
                baseUrl + (event["v"] == 1 ? "/v1/events" : "/v2/events"),
                event,
                options,
                method(:onResponse)
            );
        } catch (error) {
            _inFlight = false;
            _activeKeyHex = null;
            _requestEventId = null;
            handleFailure("retryable_failure", "Request could not be queued");
        }
    }

    function onResponse(responseCode, data, eventId) {
        if (!_inFlight
            || _statusQuery != null
            || eventId != _requestEventId) {
            return;
        }
        _inFlight = false;
        if (_queue.size() == 0 || _queue[0]["event_id"] != _requestEventId) {
            _activeKeyHex = null;
            _requestEventId = null;
            setState("RESULT UNKNOWN", "Persistent queue changed during request");
            return;
        }
        var event = _queue[0];
        var keyHex = _activeKeyHex;
        _activeKeyHex = null;
        _requestEventId = null;

        if (responseCode == 202) {
            var verified = false;
            try {
                verified = PanicProtocol.verifyDurablyAccepted(data, event, keyHex);
            } catch (error) {
                verified = false;
            }
            if (verified) {
                var remaining = copyQueue(1);
                if (!persistState(remaining, _activeIncident)) {
                    setState("RESULT UNKNOWN", "Relay accepted; local removal failed");
                    return;
                }
                _retryCount = 0;
                setState("RELAY ACCEPTED", "Provider evidence remains separate");
                if (_queue.size() > 0) {
                    sendPending();
                }
            } else {
                handleFailure("result_unknown", "Unsigned or mismatched relay result");
            }
            return;
        }

        var result = responseCode < 0
            ? transportFailure(responseCode)
            : PanicProtocol.failureResult(data);
        handleFailure(result, "Pending event retained");
    }

    function handleFailure(result, detail) {
        if (result == "configuration_failure") {
            setState("CONFIGURATION FAILURE", detail);
            return;
        }
        if (result == "retryable_failure") {
            setState("RETRYABLE FAILURE", detail);
        } else {
            setState("RESULT UNKNOWN", detail);
        }
        if (_retryCount < MAX_INITIAL_RETRIES && _queue.size() > 0) {
            _retryCount += 1;
            try {
                _retryTimer.stop();
            } catch (error) {
            }
            try {
                _retryTimer.start(method(:retryPending), RETRY_DELAY_MS, false);
            } catch (error) {
                setState("RETRYABLE FAILURE", "START retries immutable event");
            }
        }
    }

    function retryPending() {
        if (!_inFlight && _queue.size() > 0) {
            sendPending();
        }
    }

    function transportFailure(responseCode) {
        if (responseCode == Communications.BLE_QUEUE_FULL
            || responseCode == Communications.BLE_CONNECTION_UNAVAILABLE) {
            return "retryable_failure";
        }
        if (responseCode == Communications.BLE_REQUEST_TOO_LARGE
            || responseCode == Communications.INVALID_HTTP_HEADER_FIELDS_IN_REQUEST
            || responseCode == Communications.INVALID_HTTP_BODY_IN_REQUEST
            || responseCode == Communications.INVALID_HTTP_METHOD_IN_REQUEST
            || responseCode == Communications.SECURE_CONNECTION_REQUIRED) {
            return "configuration_failure";
        }
        return "result_unknown";
    }

    function connectionSummary() {
        try {
            var settings = System.getDeviceSettings();
            var connections = settings.connectionInfo;
            var wifi = connections[:wifi];
            if (wifi != null && wifi.state == System.CONNECTION_STATE_CONNECTED) {
                return "Wi-Fi reported connected; route not forced";
            }
            if (settings.phoneConnected) {
                return "Phone reported connected; route not forced";
            }
            return "No connection reported; request still attempted";
        } catch (error) {
            return "Connection path unavailable; request attempted";
        }
    }

    function currentTime() {
        try {
            return Time.getCurrentTime({
                :currentTimeType => Time.CURRENT_TIME_RTC
            }).value();
        } catch (error) {
            setState("CONFIGURATION FAILURE", "Watch time is not trustworthy");
            return null;
        }
    }

    function copyQueue(start) {
        var copy = [];
        for (var i = start; i < _queue.size(); i += 1) {
            copy.add(_queue[i]);
        }
        return copy;
    }

    function persistState(nextQueue, nextActive) {
        try {
            Storage.setValue(STATE_KEY, {
                "queue" => nextQueue,
                "active" => nextActive
            });
            _queue = nextQueue;
            _activeIncident = nextActive;
            if (_queue.size() > 0) {
                _displayEventId = _queue[0]["event_id"];
            } else if (_activeIncident != null) {
                _displayEventId = _activeIncident["incident_id"];
            } else {
                _displayEventId = null;
            }
            return true;
        } catch (error) {
            return false;
        }
    }

    function menuAction() {
        if (_inFlight) {
            return true;
        }
        if (_queue.size() > 0) {
            var liveIndex = -1;
            for (var i = 0; i < _queue.size(); i += 1) {
                if (_queue[i]["v"] != 1) {
                    liveIndex = i;
                    break;
                }
            }
            if (liveIndex >= 0) {
                var now = currentTime();
                if (now == null) {
                    return true;
                }
                for (var j = liveIndex; j < _queue.size(); j += 1) {
                    if (_queue[j]["v"] != 1 && now < _queue[j]["expires_at"]) {
                        setState("LIVE RETAINED", "LIVE events cannot be abandoned");
                        return true;
                    }
                }
                var incidentId = _queue[liveIndex]["incident_id"];
                var remaining = [];
                for (var k = 0; k < _queue.size(); k += 1) {
                    if (_queue[k]["v"] == 1) {
                        remaining.add(_queue[k]);
                    }
                }
                var nextActive = _activeIncident;
                if (nextActive != null && nextActive["incident_id"] == incidentId) {
                    nextActive = null;
                }
                try {
                    _retryTimer.stop();
                } catch (error) {
                }
                if (persistState(remaining, nextActive)) {
                    _mode = "TEST";
                    _retryCount = 0;
                    setState("RESULT UNKNOWN — EXPIRED", "Expired LIVE removed explicitly");
                } else {
                    setState("CONFIGURATION FAILURE", "Cannot remove expired LIVE");
                }
                return true;
            }
            if (persistState([], _activeIncident)) {
                setState("READY — TEST", "Pending TEST abandoned explicitly");
            } else {
                setState("CONFIGURATION FAILURE", "Cannot abandon pending TEST");
            }
            return true;
        }
        _mode = _mode == "TEST" ? "LIVE" : "TEST";
        setState("READY — " + _mode, _mode == "LIVE"
            ? "START creates encrypted LIVE incident"
            : "START sends non-sensitive TEST");
        return true;
    }

    function setState(state, detail) {
        _state = state;
        _detail = detail;
        WatchUi.requestUpdate();
    }
}

class PanicDelegate extends WatchUi.BehaviorDelegate {
    var _view;

    function initialize(view) {
        BehaviorDelegate.initialize();
        _view = view;
    }

    function onSelect() {
        _view.activate();
        return true;
    }

    function onMenu() {
        return _view.menuAction();
    }
}
