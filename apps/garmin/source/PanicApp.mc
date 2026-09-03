// SPDX-License-Identifier: MIT

import Toybox.Application;
import Toybox.Application.Properties;
import Toybox.Application.Storage;
import Toybox.Attention;
import Toybox.Communications;
import Toybox.Complications;
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.PersistedContent;
import Toybox.Position;
import Toybox.System;
import Toybox.Time;
import Toybox.Timer;
import Toybox.WatchUi;

class PanicApp extends Application.AppBase {
    var _view = null;

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
        _view = new PanicView();
        return [_view, new PanicDelegate(_view)];
    }

    function onSettingsChanged() {
        if (_view != null) {
            _view.settingsChanged();
        }
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
        var width = dc.getWidth();
        var height = dc.getHeight();
        var label = new WatchUi.TextArea({
            :text => "OPEN PANIC",
            :color => Graphics.COLOR_WHITE,
            :backgroundColor => Graphics.COLOR_BLACK,
            :font => [Graphics.FONT_MEDIUM, Graphics.FONT_SMALL,
                Graphics.FONT_TINY, Graphics.FONT_XTINY],
            :justification => Graphics.TEXT_JUSTIFY_CENTER,
            :locX => (width * 8) / 100,
            :locY => (height * 20) / 100,
            :width => (width * 84) / 100,
            :height => (height * 60) / 100
        });
        label.draw(dc);
    }
}

class PanicView extends WatchUi.View {
    const STATE_KEY = "event_state_v2";
    const LEGACY_PENDING_KEY = "pending_event";
    const LIVE_EXPIRY_SECONDS = 3600;
    const MAX_QUEUE = 3;
    const MAX_INITIAL_RETRIES = 2;
    const ALERT_ARM_HOLD_MS = 2500;
    const ALERT_ARM_FRAME_MS = 50;
    const ALERT_ARM_START_DEGREES = 270;
    const ACCEPTED_ACTION_FEEDBACK_MS = 180;
    const COVER_REFRESH_MS = 60000;
    const RETRY_DELAY_MS = 5000;
    const WIFI_CHECK_TIMEOUT_MS = 10000;
    const PROVIDER_REFERENCE_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";
    const MATERIAL_MOVE_E7 = 5000;
    const LOW_BATTERY_PERCENT = 20;
    const FIRST_CADENCE_SECONDS = 30;
    const MIDDLE_CADENCE_SECONDS = 120;
    const LATE_CADENCE_SECONDS = 300;
    const LEGACY_STATE_KEYS = ["queue", "active"];
    const STATE_KEYS = ["queue", "active", "direct_result"];
    const LEGACY_DIRECT_RESULT_KEYS = ["event_id", "request", "receipt"];
    const LEGACY_DIRECT_TRACKING_KEYS = [
        "event_id",
        "request",
        "receipt",
        "tracking_expires_at",
        "next_location_sequence",
        "last_location_hex",
        "last_location_queued_at",
        "capture_stage",
        "pending_location_hex"
    ];
    const UNBOUND_DIRECT_RESULT_KEYS = [
        "event_id",
        "request",
        "receipt",
        "pushover_accepted",
        "grafana_accepted",
        "grafana_alert_pending",
        "tracking_expires_at",
        "next_location_sequence",
        "last_location_hex",
        "last_location_queued_at",
        "capture_stage",
        "pending_location_hex",
        "pending_location_pushover",
        "pending_location_grafana"
    ];
    const DIRECT_RESULT_KEYS = [
        "event_id",
        "request",
        "receipt",
        "pushover_accepted",
        "pushover_fingerprint",
        "grafana_accepted",
        "grafana_fingerprint",
        "grafana_alert_pending",
        "accepted_at",
        "tracking_expires_at",
        "next_location_sequence",
        "last_location_hex",
        "last_location_queued_at",
        "capture_stage",
        "pending_location_hex",
        "pending_location_pushover",
        "pending_location_grafana"
    ];
    const ACTIVE_KEYS = [
        "incident_id",
        "expires_at",
        "next_sequence",
        "last_location_hex",
        "last_location_queued_at",
        "capture_stage"
    ];

    var _state = "READY — TEST";
    var _detail = "Hold top button 2.5 seconds";
    var _queue = [];
    var _activeIncident = null;
    var _directResult = null;
    var _displayEventId = null;
    var _mode = "TEST";
    var _inFlight = false;
    var _activeKeyHex = null;
    var _requestEventId = null;
    var _requestProviderFingerprint = null;
    var _statusQuery = null;
    var _retryCount = 0;
    var _retryTimer;
    var _locationExpiryTimer;
    var _statusTimer;
    var _visible = false;
    var _personalLive = false;
    var _armingAlert = false;
    var _armStartedAtMs = 0;
    var _wifiCheckPending = false;
    var _wifiFallbackEventId = null;
    var _directLocationRetryBlocked = false;
    var _directGrafanaRetryBlocked = false;
    var _acceptedStatusVisible = false;
    var _acceptedActionFeedback = null;

    function initialize() {
        View.initialize();
        _retryTimer = new Timer.Timer();
        _locationExpiryTimer = new Timer.Timer();
        _statusTimer = new Timer.Timer();
        loadState();
        selectStartupMode();
    }

    function onShow() {
        _visible = true;
        _directLocationRetryBlocked = false;
        _directGrafanaRetryBlocked = false;
        if (_queue.size() > 0) {
            sendPending();
        }
        if (_directResult != null) {
            if (_directResult["grafana_alert_pending"]
                && hasDirectGrafanaConfiguration()) {
                sendDirectGrafanaAlert();
            } else {
                resumeDirectLocations();
            }
        } else {
            resumeLocations();
        }
        scheduleIdleCoverRefresh();
    }

    function onHide() {
        _visible = false;
        _acceptedStatusVisible = false;
        _acceptedActionFeedback = null;
        cancelAlertArm();
        stopLocations();
        try {
            _retryTimer.stop();
        } catch (error) {
        }
        _wifiCheckPending = false;
    }

    function shouldShowCover() {
        return _directResult != null
            && !_acceptedStatusVisible
            && !PanicProtocol.stringEquals(_state, "LOCATION SCRUB UNSAVED")
            && !PanicProtocol.stringEquals(_state, "LOCATION STATE UNSAVED")
            && !PanicProtocol.stringEquals(_state, "ROUTE CHANGED");
    }

    function shouldShowAcceptedStatus() {
        return _directResult != null
            && _acceptedStatusVisible
            && PanicProtocol.stringEquals(_state, "PROVIDER ACCEPTED")
            && !PanicProtocol.stringEquals(_state, "LOCATION SCRUB UNSAVED")
            && !PanicProtocol.stringEquals(_state, "LOCATION STATE UNSAVED")
            && !PanicProtocol.stringEquals(_state, "ROUTE CHANGED");
    }

    function scheduleIdleCoverRefresh() {
        if (!_visible || _directResult == null) {
            return;
        }
        try {
            _statusTimer.stop();
        } catch (error) {
        }
        try {
            _statusTimer.start(method(:refreshIdleCover), COVER_REFRESH_MS, false);
        } catch (error) {
            // A frozen cover is safer than turning a timer failure into a trigger.
        }
    }

    function refreshIdleCover() {
        if (!_visible || _directResult == null) {
            return;
        }
        WatchUi.requestUpdate();
        scheduleIdleCoverRefresh();
    }

    function drawAnalogCover(dc) {
        var width = dc.getWidth();
        var height = dc.getHeight();
        var minSize = width < height ? width : height;
        var compactRound = width == height && minSize < 220;
        var centerX = compactRound ? (width * 43) / 100 : width / 2;
        var centerY = compactRound ? (height * 57) / 100 : height / 2;
        var edgePadding = minSize / 15;
        if (edgePadding < 10) {
            edgePadding = 10;
        }
        var radius = minSize / 2 - edgePadding - (compactRound ? minSize / 12 : 0);
        var majorInset = radius / 10;
        var minorInset = radius / 18;
        var majorPen = minSize >= 400 ? 4 : 3;
        var minorPen = minSize >= 300 ? 2 : 1;

        dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_BLACK);
        dc.setPenWidth(minorPen);
        dc.drawCircle(centerX, centerY, radius + edgePadding / 3);
        for (var i = 0; i < 12; i += 1) {
            var angle = (i / 12.0) * Math.PI * 2 - Math.PI / 2;
            var inset = i % 3 == 0 ? majorInset : minorInset;
            dc.setPenWidth(i % 3 == 0 ? majorPen : minorPen);
            dc.drawLine(
                centerX + (radius - inset) * Math.cos(angle),
                centerY + (radius - inset) * Math.sin(angle),
                centerX + radius * Math.cos(angle),
                centerY + radius * Math.sin(angle)
            );
        }

        dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
        var numberInset = radius / 6;
        dc.drawText(centerX, centerY - radius + numberInset, Graphics.FONT_XTINY, "12",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        if (minSize >= 220) {
            dc.drawText(centerX + radius - numberInset, centerY, Graphics.FONT_XTINY, "3",
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
            dc.drawText(centerX - radius + numberInset, centerY, Graphics.FONT_XTINY, "9",
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
        dc.drawText(centerX, centerY + radius - numberInset, Graphics.FONT_XTINY, "6",
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);

        var clock = System.getClockTime();
        var minuteAngle = (clock.min / 60.0) * Math.PI * 2 - Math.PI / 2;
        var hourAngle = ((((clock.hour % 12) * 60) + clock.min) / 720.0)
            * Math.PI * 2 - Math.PI / 2;
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.setPenWidth(minSize >= 400 ? 7 : (minSize >= 260 ? 5 : 3));
        drawHand(dc, centerX, centerY, hourAngle, radius * 0.50);
        dc.setPenWidth(minSize >= 400 ? 4 : (minSize >= 260 ? 3 : 2));
        drawHand(dc, centerX, centerY, minuteAngle, radius * 0.72);
        dc.fillCircle(centerX, centerY, minSize >= 400 ? 7 : 4);
        dc.setPenWidth(1);
    }

    function drawHand(dc, centerX, centerY, angle, length) {
        dc.drawLine(
            centerX,
            centerY,
            centerX + length * Math.cos(angle),
            centerY + length * Math.sin(angle)
        );
    }

    function drawAcceptedStatus(dc) {
        var width = dc.getWidth();
        var height = dc.getHeight();
        var isRound = width == height;
        var compactRound = isRound && width < 220;
        var safeWidth = (width * (compactRound ? 64 : (isRound ? 76 : 88))) / 100;
        var safeLeft = (width - safeWidth) / 2;

        var title = new WatchUi.TextArea({
            :text => "ALERT STATUS",
            :color => Graphics.COLOR_WHITE,
            :backgroundColor => Graphics.COLOR_BLACK,
            :font => [Graphics.FONT_MEDIUM, Graphics.FONT_SMALL,
                Graphics.FONT_TINY, Graphics.FONT_XTINY],
            :justification => Graphics.TEXT_JUSTIFY_CENTER,
            :locX => safeLeft,
            :locY => (height * (compactRound ? 12 : 18)) / 100,
            :width => safeWidth,
            :height => (height * (compactRound ? 18 : 15)) / 100
        });
        title.draw(dc);

        var detail = new WatchUi.TextArea({
            :text => acceptedProviderSummary()
                + "\nDelivery not confirmed",
            :color => Graphics.COLOR_LT_GRAY,
            :backgroundColor => Graphics.COLOR_BLACK,
            :font => [Graphics.FONT_TINY, Graphics.FONT_XTINY],
            :justification => Graphics.TEXT_JUSTIFY_CENTER,
            :locX => safeLeft,
            :locY => (height * (compactRound ? 34 : 36)) / 100,
            :width => safeWidth,
            :height => (height * (compactRound ? 30 : 25)) / 100
        });
        detail.draw(dc);
        drawAcceptedButtonIndicators(dc);

        if (_acceptedActionFeedback != null) {
            var feedback = new WatchUi.TextArea({
                :text => PanicProtocol.stringEquals(_acceptedActionFeedback, "RESET")
                    ? "RESET TEST"
                    : "DIAL",
                :color => Graphics.COLOR_WHITE,
                :backgroundColor => Graphics.COLOR_BLACK,
                :font => [Graphics.FONT_MEDIUM, Graphics.FONT_SMALL,
                    Graphics.FONT_TINY, Graphics.FONT_XTINY],
                :justification => Graphics.TEXT_JUSTIFY_CENTER,
                :locX => safeLeft,
                :locY => (height * (compactRound ? 68 : 67)) / 100,
                :width => safeWidth,
                :height => (height * 16) / 100
            });
            feedback.draw(dc);
        }
    }

    function drawAcceptedButtonIndicators(dc) {
        var width = dc.getWidth();
        var height = dc.getHeight();
        var resetActive = PanicProtocol.stringEquals(_acceptedActionFeedback, "RESET");
        var dialActive = PanicProtocol.stringEquals(_acceptedActionFeedback, "DIAL");
        if (width == height) {
            var radius = width / 2 - (width >= 400 ? 12 : 7);
            drawAcceptedButtonIndicator(dc, width / 2, height / 2,
                radius, 180, resetActive);
            drawAcceptedButtonIndicator(dc, width / 2, height / 2,
                radius, 225, dialActive);
            return;
        }
        drawAcceptedEdgeIndicator(dc, height / 2, resetActive);
        drawAcceptedEdgeIndicator(dc, (height * 72) / 100, dialActive);
    }

    function drawAcceptedButtonIndicator(dc, centerX, centerY, radius,
        centerDegrees, active) {
        var minSize = dc.getWidth() < dc.getHeight() ? dc.getWidth() : dc.getHeight();
        var halfSweep = active ? 18 : 7;
        dc.setPenWidth(active
            ? (minSize >= 400 ? 16 : (minSize >= 260 ? 12 : 8))
            : (minSize >= 400 ? 8 : (minSize >= 260 ? 6 : 4)));
        dc.setColor(active ? Graphics.COLOR_WHITE : Graphics.COLOR_DK_GRAY,
            Graphics.COLOR_BLACK);
        dc.drawArc(centerX, centerY, radius, Graphics.ARC_CLOCKWISE,
            centerDegrees + halfSweep, centerDegrees - halfSweep);
        dc.setPenWidth(1);
    }

    function drawAcceptedEdgeIndicator(dc, centerY, active) {
        var height = dc.getHeight();
        var halfLength = active ? height / 12 : height / 24;
        dc.setPenWidth(active ? 12 : 6);
        dc.setColor(active ? Graphics.COLOR_WHITE : Graphics.COLOR_DK_GRAY,
            Graphics.COLOR_BLACK);
        dc.drawLine(5, centerY - halfLength, 5, centerY + halfLength);
        dc.setPenWidth(1);
    }

    function selectStartupMode() {
        refreshConfiguredMode();
        if (!PanicProtocol.stringEquals(_state, "READY — TEST")
            || _queue.size() > 0
            || _activeIncident != null
            || _directResult != null) {
            return;
        }
        if (PanicProtocol.stringEquals(_mode, "DIRECT_TEST")) {
            _state = "READY — TEST";
            _detail = "Hold top button 2.5 seconds";
        } else if (_personalLive) {
            _state = "READY — LIVE";
            _detail = "Hold top button 2.5 seconds";
        } else if (hasRelayTestConfiguration()) {
            _detail = "Hold top button 2.5 seconds";
        } else {
            _state = "SETUP REQUIRED";
            _detail = "Connect IQ Store: add webhook or keys";
        }
    }

    function refreshConfiguredMode() {
        _personalLive = hasProvisionedLiveConfiguration();
        _mode = hasDirectAlertConfiguration()
            ? "DIRECT_TEST"
            : (_personalLive ? "LIVE" : "TEST");
    }

    function settingsChanged() {
        if (_activeIncident != null) {
            return;
        }
        refreshConfiguredMode();
        if (_directResult != null) {
            if (_directResult["grafana_alert_pending"]
                && hasDirectGrafanaConfiguration()
                && !_inFlight) {
                _directGrafanaRetryBlocked = false;
                sendDirectGrafanaAlert();
            } else if (_directResult["pending_location_hex"].length() > 0
                && !_inFlight) {
                _retryCount = 0;
                _directLocationRetryBlocked = false;
                sendDirectLocation();
            } else if (!_inFlight) {
                resumeDirectLocations();
            }
            return;
        }
        if (_queue.size() > 0) {
            if (_queue[0]["v"] == 1 && !_inFlight) {
                _retryCount = 0;
                sendPending();
            }
            WatchUi.requestUpdate();
            return;
        }
        _state = "READY — TEST";
        _detail = "Hold top button 2.5 seconds";
        selectStartupMode();
        WatchUi.requestUpdate();
    }

    function hasRelayTestConfiguration() {
        try {
            return PanicProtocol.isHttpsBaseUrl(Properties.getValue("relayBaseUrl"))
                && PanicProtocol.isCanonicalId(Properties.getValue("deviceId"))
                && PanicProtocol.isSafeAuthKey(Properties.getValue("hmacKeyHex"));
        } catch (error) {
            return false;
        }
    }

    function hasDirectPushoverConfiguration() {
        try {
            return DirectPushoverAdapter.isConfigured();
        } catch (error) {
            return false;
        }
    }

    function hasDirectGrafanaConfiguration() {
        try {
            return DirectGrafanaAdapter.isConfigured();
        } catch (error) {
            return false;
        }
    }

    function hasDirectAlertConfiguration() {
        return hasDirectPushoverConfiguration() || hasDirectGrafanaConfiguration();
    }

    function hasBoundDirectPushover() as Lang.Boolean {
        try {
            return _directResult != null
                && DirectAlertSafety.isActiveRoute(
                    _directResult["pushover_accepted"],
                    _directResult["pushover_fingerprint"],
                    DirectPushoverAdapter.configurationFingerprint()
                );
        } catch (error) {
            return false;
        }
    }

    function hasBoundDirectGrafana() as Lang.Boolean {
        try {
            return _directResult != null
                && DirectAlertSafety.isActiveRoute(
                    _directResult["grafana_accepted"],
                    _directResult["grafana_fingerprint"],
                    DirectGrafanaAdapter.configurationFingerprint()
                );
        } catch (error) {
            return false;
        }
    }

    function hasBoundDirectProvider() as Lang.Boolean {
        return hasBoundDirectPushover() || hasBoundDirectGrafana();
    }

    function hasProvisionedLiveConfiguration() {
        try {
            return PanicProtocol.isHttpsBaseUrl(Properties.getValue("relayBaseUrl"))
                && PanicProtocol.isCanonicalId(Properties.getValue("deviceId"))
                && PanicProtocol.isSafeLiveConfiguration(
                    Properties.getValue("liveAuthKeyHex"),
                    Properties.getValue("liveEncKeyHex"),
                    Properties.getValue("liveMacKeyHex"),
                    Properties.getValue("liveTemplateIdHex"),
                    Properties.getValue("liveKeyVersion")
                );
        } catch (error) {
            return false;
        }
    }

    function loadState() {
        try {
            var stored = Storage.getValue(STATE_KEY);
            if (stored == null) {
                migrateLegacyTest();
                return;
            }
            if (PanicProtocol.hasExactKeys(stored, LEGACY_STATE_KEYS)) {
                var legacyState = stored as Lang.Dictionary;
                stored = {
                    "queue" => legacyState["queue"],
                    "active" => legacyState["active"],
                    "direct_result" => null
                };
                Storage.setValue(STATE_KEY, stored);
            }
            var storedState = stored as Lang.Dictionary;
            if (PanicProtocol.hasExactKeys(storedState, STATE_KEYS)
                && storedState["direct_result"] != null
                && PanicProtocol.hasExactKeys(
                    storedState["direct_result"],
                    LEGACY_DIRECT_RESULT_KEYS
                )) {
                clearPreviousDirectState();
                return;
            }
            if (PanicProtocol.hasExactKeys(storedState, STATE_KEYS)
                && storedState["direct_result"] != null
                && PanicProtocol.hasExactKeys(
                    storedState["direct_result"],
                    LEGACY_DIRECT_TRACKING_KEYS
                )) {
                clearPreviousDirectState();
                return;
            }
            if (PanicProtocol.hasExactKeys(storedState, STATE_KEYS)
                && storedState["direct_result"] != null
                && PanicProtocol.hasExactKeys(
                    storedState["direct_result"],
                    UNBOUND_DIRECT_RESULT_KEYS
                )) {
                // Pre-fingerprint beta state cannot safely retain or retarget GPS.
                // Delete the whole mutually-exclusive direct state, including any
                // plaintext coordinates, and require a fresh TEST activation.
                clearPreviousDirectState();
                return;
            }
            if (!validStoredState(stored)) {
                if (isRecoverableInvalidDirectTestState(stored)) {
                    clearInvalidDirectTestState();
                    return;
                }
                setState("CONFIGURATION FAILURE", "Stored event state is invalid");
                return;
            }
            var state = stored as Lang.Dictionary;
            _queue = state["queue"] as Lang.Array;
            _activeIncident = state["active"] as Lang.Dictionary or Null;
            _directResult = state["direct_result"] as Lang.Dictionary or Null;
            if (_queue.size() > 0) {
                _displayEventId = _queue[0]["event_id"];
                setState(_queue[0]["v"] == 1 ? "TEST PENDING" : "PENDING",
                    _queue[0]["v"] == 1
                        ? "MENU clears; top button retries"
                        : "Top button retries immutable event");
            } else if (_activeIncident != null) {
                _displayEventId = _activeIncident["incident_id"];
                setState("INCIDENT ACTIVE", "No event is waiting for relay");
            } else if (_directResult != null) {
                _displayEventId = _directResult["event_id"];
                setState("PROVIDER ACCEPTED", "Human response remains unknown");
            }
        } catch (error) {
            setState("CONFIGURATION FAILURE", "Cannot read persistent storage");
        }
    }

    function clearPreviousDirectState() as Void {
        Storage.deleteValue(STATE_KEY);
        _queue = [];
        _activeIncident = null;
        _directResult = null;
        setState("READY — TEST", "Previous beta GPS state cleared");
    }

    function isRecoverableInvalidDirectTestState(value) {
        return PanicProtocol.hasExactKeys(value, STATE_KEYS)
            && value["queue"] instanceof Lang.Array
            && value["queue"].size() == 0
            && value["active"] == null
            && value["direct_result"] != null;
    }

    function clearInvalidDirectTestState() as Void {
        Storage.deleteValue(STATE_KEY);
        _queue = [];
        _activeIncident = null;
        _directResult = null;
        setState("READY — TEST", "Invalid previous TEST state cleared");
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
        var legacyEvent = legacy as Lang.Dictionary;
        var migrated = [legacyEvent];
        Storage.setValue(STATE_KEY, {
            "queue" => migrated,
            "active" => null,
            "direct_result" => null
        });
        Storage.deleteValue(LEGACY_PENDING_KEY);
        _queue = migrated;
        _displayEventId = legacyEvent["event_id"];
        setState("TEST PENDING", "MENU clears; top button retries");
    }

    function validStoredState(value) {
        if (!PanicProtocol.hasExactKeys(value, STATE_KEYS)
            || !(value["queue"] instanceof Lang.Array)
            || value["queue"].size() > MAX_QUEUE
            || !validActive(value["active"])
            || !validDirectResult(value["direct_result"])
            || (value["direct_result"] != null
                && (value["queue"].size() > 0 || value["active"] != null))) {
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
                    if (!PanicProtocol.stringEquals(
                            queue[i]["incident_id"],
                            value["active"]["incident_id"]
                        )
                        || queue[i]["expires_at"] != value["active"]["expires_at"]) {
                        return false;
                    }
                } else if (archivedIncidentId.length() == 0) {
                    archivedIncidentId = queue[i]["incident_id"];
                    archivedExpiresAt = queue[i]["expires_at"];
                } else if (!PanicProtocol.stringEquals(
                        queue[i]["incident_id"],
                        archivedIncidentId
                    )
                    || queue[i]["expires_at"] != archivedExpiresAt) {
                    return false;
                }
            }
        }
        return true;
    }

    function validDirectResult(value) {
        if (value == null) {
            return true;
        }
        if (!PanicProtocol.hasExactKeys(value, DIRECT_RESULT_KEYS)) {
            return false;
        }
        var result = value as Lang.Dictionary;
        return validDirectAcceptance(result)
            && validDirectTracking(result)
            && validDirectPendingLocation(result);
    }

    function validDirectAcceptance(result) {
        if (!PanicProtocol.isCanonicalId(result["event_id"])
            || !(result["pushover_accepted"] instanceof Lang.Boolean)
            || !(result["grafana_accepted"] instanceof Lang.Boolean)
            || !(result["grafana_alert_pending"] instanceof Lang.Boolean)) {
            return false;
        }
        if (!result["pushover_accepted"] && !result["grafana_accepted"]) {
            return false;
        }
        if (result["pushover_accepted"]) {
            if (!isProviderReference(result["request"])
                || !DirectPushoverAdapter.isToken(result["receipt"])) {
                return false;
            }
        } else if (!PanicProtocol.stringEquals(result["request"], "")
            || !PanicProtocol.stringEquals(result["receipt"], "")) {
            return false;
        }
        if (result["grafana_accepted"] && result["grafana_alert_pending"]) {
            return false;
        }
        return validDirectProviderBindings(result);
    }

    function validDirectTracking(result) {
        if (!(result["accepted_at"] instanceof Lang.Number)
            || result["accepted_at"] < 0
            || !(result["tracking_expires_at"] instanceof Lang.Number)
            || result["tracking_expires_at"] < 0
            || !validDirectTrackingWindow(result)
            || !(result["next_location_sequence"] instanceof Lang.Number)
            || result["next_location_sequence"] < 1
            || !validLocationHex(result["last_location_hex"])
            || !(result["last_location_queued_at"] instanceof Lang.Number)
            || result["last_location_queued_at"] < 0) {
            return false;
        }
        if (result["tracking_expires_at"] == 0) {
            if (result["last_location_queued_at"] != 0) {
                return false;
            }
        } else if (result["last_location_queued_at"] > result["tracking_expires_at"]) {
            return false;
        }
        return result["capture_stage"] instanceof Lang.Number
            && result["capture_stage"] >= 0
            && result["capture_stage"] <= 3;
    }

    function validDirectPendingLocation(result) {
        if (!(result["capture_stage"] instanceof Lang.Number)
            || !validLocationHex(result["pending_location_hex"])
            || !(result["pending_location_pushover"] instanceof Lang.Boolean)
            || !(result["pending_location_grafana"] instanceof Lang.Boolean)) {
            return false;
        }
        if ((result["pending_location_pushover"] && !result["pushover_accepted"])
            || (result["pending_location_grafana"] && !result["grafana_accepted"])) {
            return false;
        }
        var hasPendingLocation = result["pending_location_hex"].length() > 0;
        var hasPendingTarget = result["pending_location_pushover"]
            || result["pending_location_grafana"];
        if (hasPendingLocation != hasPendingTarget) {
            return false;
        }
        if ((result["capture_stage"] == 0 || result["capture_stage"] == 3)
            && (result["last_location_hex"].length() > 0
                || result["last_location_queued_at"] != 0
                || hasPendingLocation)) {
            return false;
        }
        return true;
    }

    function validDirectProviderBindings(value) {
        var pushoverFingerprint = value["pushover_fingerprint"];
        var grafanaFingerprint = value["grafana_fingerprint"];
        if (value["pushover_accepted"]) {
            if (!PanicProtocol.isCanonicalDigest(pushoverFingerprint)) {
                return false;
            }
        } else if (!PanicProtocol.stringEquals(pushoverFingerprint, "")) {
            return false;
        }
        if (value["grafana_accepted"]) {
            if (!PanicProtocol.isCanonicalDigest(grafanaFingerprint)) {
                return false;
            }
        } else if (!PanicProtocol.stringEquals(grafanaFingerprint, "")) {
            return false;
        }
        return true;
    }

    function validDirectTrackingWindow(value) {
        if (value["tracking_expires_at"] == 0) {
            return value["accepted_at"] == 0;
        }
        return value["accepted_at"] > 0
            && value["accepted_at"] <= PanicProtocol.MAX_TIME - LIVE_EXPIRY_SECONDS
            && value["tracking_expires_at"]
                == value["accepted_at"] + LIVE_EXPIRY_SECONDS;
    }

    function validLocationHex(value) {
        return value instanceof Lang.String
            && (value.length() == 0 || PanicProtocol.isLowerHex(value, 32));
    }

    function isProviderReference(value) {
        if (!(value instanceof Lang.String)
            || value.length() < 1
            || value.length() > 128) {
            return false;
        }
        var characters = value.toCharArray();
        for (var i = 0; i < characters.size(); i += 1) {
            if (PROVIDER_REFERENCE_ALPHABET.find(characters[i].toString()) == null) {
                return false;
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
        if (shouldShowCover()) {
            drawAnalogCover(dc);
            return;
        }
        if (shouldShowAcceptedStatus()) {
            drawAcceptedStatus(dc);
            return;
        }
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        var width = dc.getWidth();
        var height = dc.getHeight();
        var isRound = width == height;
        var compactRound = isRound && width < 220;
        var safeWidth = (width * (compactRound ? 62 : (isRound ? 76 : 88))) / 100;
        var safeLeft = compactRound ? (width * 6) / 100 : (width - safeWidth) / 2;
        var title = new WatchUi.TextArea({
            :text => _state,
            :color => Graphics.COLOR_WHITE,
            :backgroundColor => Graphics.COLOR_BLACK,
            :font => [Graphics.FONT_LARGE, Graphics.FONT_MEDIUM,
                Graphics.FONT_SMALL, Graphics.FONT_TINY, Graphics.FONT_XTINY],
            :justification => Graphics.TEXT_JUSTIFY_CENTER,
            :locX => safeLeft,
            :locY => (height * (compactRound ? 14 : 18)) / 100,
            :width => safeWidth,
            :height => (height * (compactRound ? 34 : 27)) / 100
        });
        title.draw(dc);

        var detail = new WatchUi.TextArea({
            :text => _detail,
            :color => Graphics.COLOR_LT_GRAY,
            :backgroundColor => Graphics.COLOR_BLACK,
            :font => [Graphics.FONT_SMALL, Graphics.FONT_TINY,
                Graphics.FONT_XTINY],
            :justification => Graphics.TEXT_JUSTIFY_CENTER,
            :locX => safeLeft,
            :locY => (height * (compactRound ? 50 : 47)) / 100,
            :width => safeWidth,
            :height => (height * (compactRound ? 34 : 29)) / 100
        });
        detail.draw(dc);
        if (_displayEventId != null) {
            var tracking = new WatchUi.TextArea({
                :text => compactDisplayId(_displayEventId),
                :color => Graphics.COLOR_DK_GRAY,
                :backgroundColor => Graphics.COLOR_BLACK,
                :font => [Graphics.FONT_TINY, Graphics.FONT_XTINY],
                :justification => Graphics.TEXT_JUSTIFY_CENTER,
                :locX => safeLeft,
                :locY => (height * (compactRound ? 86 : 80)) / 100,
                :width => safeWidth,
                :height => (height * 12) / 100
            });
            tracking.draw(dc);
        }
        if (_armingAlert) {
            drawAlertArmProgress(dc);
        }
    }

    function compactDisplayId(value) {
        if (!(value instanceof Lang.String)) {
            return "ID unavailable";
        }
        var id = value as Lang.String;
        if (id.length() <= 10) {
            return "ID " + id;
        }
        return "ID " + id.substring(0, 4) + "..."
            + id.substring(id.length() - 4, id.length());
    }

    function alertArmElapsedMs() {
        if (!_armingAlert) {
            return -1;
        }
        var now = System.getTimer();
        // Fail closed on the rare system-timer rollover during a hold.
        if (now < _armStartedAtMs) {
            return -1;
        }
        return now - _armStartedAtMs;
    }

    function drawAlertArmProgress(dc) {
        var elapsed = alertArmElapsedMs();
        if (elapsed < 0) {
            return;
        }
        if (elapsed > ALERT_ARM_HOLD_MS) {
            elapsed = ALERT_ARM_HOLD_MS;
        }

        var width = dc.getWidth();
        var height = dc.getHeight();
        var minSize = width < height ? width : height;
        var compactRound = width == height && minSize < 220;
        var inset = minSize / 24;
        if (inset < 6) {
            inset = 6;
        }
        var radius = minSize / 2 - inset - (compactRound ? minSize / 12 : 0);
        var centerX = compactRound ? (width * 43) / 100 : width / 2;
        var centerY = compactRound ? (height * 57) / 100 : height / 2;
        var penWidth = minSize >= 400 ? 6 : (minSize >= 260 ? 4 : 3);

        dc.setPenWidth(penWidth);
        dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_BLACK);
        dc.drawCircle(centerX, centerY, radius);

        var sweep = (elapsed * 180) / ALERT_ARM_HOLD_MS;
        if (sweep > 0) {
            var clockwiseEnd = ALERT_ARM_START_DEGREES - sweep;
            var counterClockwiseEnd = ALERT_ARM_START_DEGREES + sweep;
            if (clockwiseEnd < 0) {
                clockwiseEnd += 360;
            }
            if (counterClockwiseEnd >= 360) {
                counterClockwiseEnd -= 360;
            }
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
            dc.drawArc(
                centerX,
                centerY,
                radius,
                Graphics.ARC_CLOCKWISE,
                ALERT_ARM_START_DEGREES,
                clockwiseEnd
            );
            dc.drawArc(
                centerX,
                centerY,
                radius,
                Graphics.ARC_COUNTER_CLOCKWISE,
                ALERT_ARM_START_DEGREES,
                counterClockwiseEnd
            );
        }
        dc.setPenWidth(1);
    }

    function activate() {
        if (_inFlight) {
            return;
        }
        if (_directResult != null) {
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
                setState("INCIDENT ACTIVE", "Repeated press keeps the same incident");
                return;
            }
            expireLocations();
            return;
        }
        if (PanicProtocol.stringEquals(_mode, "LIVE")) {
            activateLive();
        } else {
            activateTest();
        }
    }

    function startActionPressed() {
        if (_armingAlert
            || _inFlight
            || _queue.size() > 0
            || _activeIncident != null
            || _directResult != null) {
            return true;
        }
        _armingAlert = true;
        _armStartedAtMs = System.getTimer();
        try {
            _retryTimer.stop();
        } catch (error) {
        }
        try {
            _retryTimer.start(method(:advanceAlertArm), ALERT_ARM_FRAME_MS, true);
            WatchUi.requestUpdate();
        } catch (error) {
            _armingAlert = false;
            _armStartedAtMs = 0;
        }
        return true;
    }

    function startActionReleased() {
        cancelAlertArm();
        return true;
    }

    function advanceAlertArm() {
        if (!_armingAlert) {
            return;
        }
        var elapsed = alertArmElapsedMs();
        if (elapsed < 0) {
            cancelAlertArm();
            return;
        }
        if (elapsed >= ALERT_ARM_HOLD_MS) {
            commitArmedAlert();
            return;
        }
        WatchUi.requestUpdate();
    }

    function cancelAlertArm() {
        if (!_armingAlert) {
            return;
        }
        _armingAlert = false;
        _armStartedAtMs = 0;
        try {
            _retryTimer.stop();
        } catch (error) {
        }
        if (_visible) {
            WatchUi.requestUpdate();
        }
    }

    function commitArmedAlert() {
        if (!_armingAlert) {
            return;
        }
        _armingAlert = false;
        _armStartedAtMs = 0;
        try {
            _retryTimer.stop();
        } catch (error) {
        }
        if (!_visible
            || _inFlight
            || _queue.size() > 0
            || _activeIncident != null
            || _directResult != null) {
            return;
        }
        activate();
    }

    function selectAction() {
        if (shouldShowAcceptedStatus()) {
            return beginAcceptedActionFeedback("DIAL");
        }
        toggleAcceptedStatus();
        return true;
    }

    function downAction() {
        if (shouldShowAcceptedStatus()) {
            return beginAcceptedActionFeedback("DIAL");
        }
        toggleAcceptedStatus();
        return true;
    }

    function acceptedProviderSummary() {
        if (_directResult == null) {
            return "Provider accepted";
        }
        var grafanaAccepted = _directResult["grafana_accepted"];
        var pushoverAccepted = _directResult["pushover_accepted"];
        if (grafanaAccepted && pushoverAccepted) {
            return "Grafana + Pushover accepted";
        }
        if (grafanaAccepted) {
            return "Grafana accepted";
        }
        if (pushoverAccepted) {
            return "Pushover accepted";
        }
        return "Provider accepted";
    }

    function toggleAcceptedStatus() {
        if (_directResult == null) {
            return;
        }
        _acceptedStatusVisible = !_acceptedStatusVisible;
        if (_acceptedStatusVisible) {
            _state = "PROVIDER ACCEPTED";
        }
        WatchUi.requestUpdate();
    }

    function beginAcceptedActionFeedback(action) {
        if (_acceptedActionFeedback != null) {
            return true;
        }
        _acceptedActionFeedback = action;
        WatchUi.requestUpdate();
        try {
            _statusTimer.stop();
        } catch (error) {
        }
        try {
            _statusTimer.start(method(:completeAcceptedActionFeedback),
                ACCEPTED_ACTION_FEEDBACK_MS, false);
        } catch (error) {
            completeAcceptedActionFeedback();
        }
        return true;
    }

    function completeAcceptedActionFeedback() {
        try {
            _statusTimer.stop();
        } catch (error) {
        }
        var action = _acceptedActionFeedback;
        _acceptedActionFeedback = null;
        if (PanicProtocol.stringEquals(action, "RESET")) {
            resetAcceptedTest();
            return;
        }
        if (PanicProtocol.stringEquals(action, "DIAL")) {
            _acceptedStatusVisible = false;
            WatchUi.requestUpdate();
            scheduleIdleCoverRefresh();
        }
    }

    function activateTest() {
        var baseUrl = Properties.getValue("relayBaseUrl");
        var deviceId = Properties.getValue("deviceId");
        var keyHex = Properties.getValue("hmacKeyHex");
        var directAlert = hasDirectAlertConfiguration();
        if (!directAlert
            && (!PanicProtocol.isHttpsBaseUrl(baseUrl)
            || !PanicProtocol.isCanonicalId(deviceId)
            || !PanicProtocol.isSafeAuthKey(keyHex))) {
            setState("SETUP REQUIRED", "Connect IQ Store: add webhook or keys");
            return;
        }
        var now = currentTime();
        if (now == null) {
            return;
        }
        if (directAlert) {
            deviceId = PanicProtocol.randomId();
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
            setState("INCIDENT ACTIVE", "Repeated press keeps the same incident");
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
        confirmDurableTrigger();

        // The alert is durable and its network submission is started before GPS.
        sendPending();
        captureLocations();
    }

    function confirmDurableTrigger() {
        try {
            if (Attention has :vibrate) {
                Attention.vibrate([new Attention.VibeProfile(25, 120)]);
            }
        } catch (error) {
            // Haptic feedback is best-effort and never changes durable state.
        }
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
            var snapshot = null;
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
        refreshConfiguredMode();
        if (_activeIncident != null && !persistState(_queue, null)) {
            setState("LOCAL DISARM UNSAVED", "Expired location state could not be scrubbed");
            return;
        }
        setState(_queue.size() > 0 ? "RESULT UNKNOWN — EXPIRED" : "INCIDENT EXPIRED",
            _queue.size() > 0
                ? "Encrypted pending events retained; MENU archives"
                : "Hold top button 2.5 seconds for a new incident");
        scheduleIdleCoverRefresh();
    }

    function resumeDirectLocations() {
        if (_directResult == null || _directResult["capture_stage"] == 3) {
            return;
        }
        var now = currentTime();
        if (now == null) {
            return;
        }
        if (now >= _directResult["tracking_expires_at"]) {
            expireDirectLocations();
            return;
        }
        captureDirectLocations();
    }

    function captureDirectLocations() {
        if (!_visible
            || _directResult == null
            || _directResult["capture_stage"] == 3) {
            return;
        }
        if (!hasBoundDirectProvider()) {
            stopLocations();
            setState("ROUTE CHANGED", "Restore the accepted provider settings for GPS");
            return;
        }
        var now = currentTime();
        if (now == null) {
            return;
        }
        if (now >= _directResult["tracking_expires_at"]) {
            expireDirectLocations();
            return;
        }
        scheduleDirectLocationExpiry(now);
        if (_directResult["pending_location_hex"].length() > 0) {
            sendDirectLocation();
        } else if (_directResult["capture_stage"] == 0) {
            var snapshot = null;
            try {
                snapshot = Position.getInfo();
            } catch (error) {
            }
            if (!queueDirectLocation(snapshot, 0, 1)) {
                if (!persistDirectTracking(
                        _directResult["next_location_sequence"],
                        _directResult["last_location_hex"],
                        _directResult["last_location_queued_at"],
                        1,
                        "",
                        false,
                        false
                    )) {
                    _directLocationRetryBlocked = true;
                    setState("LOCATION STATE UNSAVED", "Reopen to retry local GPS state");
                    return;
                }
            }
        }
        startDirectContinuousLocations();
    }

    function startDirectContinuousLocations() {
        if (!_visible
            || _directResult == null
            || _directResult["capture_stage"] == 0
            || _directResult["capture_stage"] == 3) {
            return;
        }
        try {
            Position.enableLocationEvents(Position.LOCATION_CONTINUOUS, method(:onPosition));
        } catch (error) {
            // The accepted alert remains valid; reopening retries real GPS acquisition.
        }
    }

    function scheduleDirectLocationExpiry(now) {
        try {
            _locationExpiryTimer.stop();
        } catch (error) {
        }
        var remaining = _directResult["tracking_expires_at"] - now;
        if (remaining <= 0) {
            expireDirectLocations();
            return;
        }
        try {
            _locationExpiryTimer.start(
                method(:expireDirectLocations),
                remaining * 1000,
                false
            );
        } catch (error) {
            // Every callback and reopen independently enforces the same expiry.
        }
    }

    function expireDirectLocations() {
        stopLocations();
        if (_directResult == null || _directResult["capture_stage"] == 3) {
            return;
        }
        if (!persistDirectTracking(
            _directResult["next_location_sequence"],
            "",
            0,
            3,
            "",
            false,
            false
        )) {
            setState("LOCATION SCRUB UNSAVED", "Retrying local coordinate removal");
            try {
                _retryTimer.stop();
            } catch (error) {
            }
            try {
                _retryTimer.start(method(:expireDirectLocations), RETRY_DELAY_MS, false);
            } catch (error) {
                // Reopening the app retries expiry and scrubbing.
            }
            return;
        }
        setState("PROVIDER ACCEPTED", "GPS expired; local coordinates scrubbed");
        scheduleIdleCoverRefresh();
    }

    function queueDirectLocation(info, path, nextCaptureStage) {
        if (_directResult == null
            || _directResult["capture_stage"] == 3
            || _directResult["pending_location_hex"].length() > 0
            || !hasBoundDirectProvider()
            || info == null
            || info.position == null
            || info.when == null
            || info.accuracy == Position.QUALITY_NOT_AVAILABLE) {
            return false;
        }
        var now = currentTime();
        if (now == null
            || now >= _directResult["tracking_expires_at"]
            || !DirectAlertSafety.isFreshCapture(
                _directResult["accepted_at"],
                info.when.value(),
                now
            )) {
            return false;
        }
        var record = PanicProtocol.locationRecord(info, path);
        var captureAt = record.decodeNumber(Lang.NUMBER_FORMAT_UINT32, {
            :offset => 2,
            :endianness => Lang.ENDIAN_BIG
        });
        if (captureAt == 0) {
            return false;
        }
        var recordHex = PanicProtocol.bytesHex(record);
        if (PanicProtocol.stringEquals(recordHex, _directResult["last_location_hex"])) {
            return false;
        }
        var pendingPushover = hasBoundDirectPushover();
        var pendingGrafana = hasBoundDirectGrafana();
        if (!pendingPushover && !pendingGrafana) {
            return false;
        }
        if (!persistDirectTracking(
                _directResult["next_location_sequence"],
                _directResult["last_location_hex"],
                _directResult["last_location_queued_at"],
                nextCaptureStage,
                recordHex,
                pendingPushover,
                pendingGrafana
            )) {
            return false;
        }
        sendDirectLocation();
        return true;
    }

    function shouldQueueDirectCadenceLocation(info, now) {
        if (_directResult == null
            || _directResult["pending_location_hex"].length() > 0
            || !hasBoundDirectProvider()
            || info == null
            || info.position == null
            || info.when == null
            || info.accuracy == Position.QUALITY_NOT_AVAILABLE
            || !DirectAlertSafety.isFreshCapture(
                _directResult["accepted_at"],
                info.when.value(),
                now
            )) {
            return false;
        }
        if (_directResult["last_location_hex"].length() == 0) {
            return true;
        }
        var record = PanicProtocol.locationRecord(info, 1);
        var previous = PanicProtocol.hexBytes(_directResult["last_location_hex"]);
        var captureAt = record.decodeNumber(Lang.NUMBER_FORMAT_UINT32, {
            :offset => 2,
            :endianness => Lang.ENDIAN_BIG
        });
        if (captureAt == 0) {
            return false;
        }
        if (record[14] > previous[14]) {
            return true;
        }
        var lastQueuedAt = _directResult["last_location_queued_at"];
        if (now < lastQueuedAt
            || now - lastQueuedAt < cadenceSecondsForExpiry(
                now,
                _directResult["tracking_expires_at"]
            )) {
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

    function persistDirectTracking(
        nextSequence,
        lastLocationHex,
        lastLocationQueuedAt,
        captureStage,
        pendingLocationHex,
        pendingLocationPushover,
        pendingLocationGrafana
    ) {
        if (_directResult == null) {
            return false;
        }
        return persistStateWithDirect(_queue, _activeIncident, {
            "event_id" => _directResult["event_id"],
            "request" => _directResult["request"],
            "receipt" => _directResult["receipt"],
            "pushover_accepted" => _directResult["pushover_accepted"],
            "pushover_fingerprint" => _directResult["pushover_fingerprint"],
            "grafana_accepted" => _directResult["grafana_accepted"],
            "grafana_fingerprint" => _directResult["grafana_fingerprint"],
            "grafana_alert_pending" => _directResult["grafana_alert_pending"],
            "accepted_at" => _directResult["accepted_at"],
            "tracking_expires_at" => _directResult["tracking_expires_at"],
            "next_location_sequence" => nextSequence,
            "last_location_hex" => lastLocationHex,
            "last_location_queued_at" => lastLocationQueuedAt,
            "capture_stage" => captureStage,
            "pending_location_hex" => pendingLocationHex,
            "pending_location_pushover" => pendingLocationPushover,
            "pending_location_grafana" => pendingLocationGrafana
        });
    }

    function persistDirectProviderState(
        grafanaAccepted,
        grafanaFingerprint,
        grafanaAlertPending
    ) {
        if (_directResult == null) {
            return false;
        }
        var pendingGrafana = _directResult["pending_location_grafana"];
        if (grafanaAccepted && _directResult["pending_location_hex"].length() > 0) {
            pendingGrafana = true;
        }
        return persistStateWithDirect(_queue, _activeIncident, {
            "event_id" => _directResult["event_id"],
            "request" => _directResult["request"],
            "receipt" => _directResult["receipt"],
            "pushover_accepted" => _directResult["pushover_accepted"],
            "pushover_fingerprint" => _directResult["pushover_fingerprint"],
            "grafana_accepted" => grafanaAccepted,
            "grafana_fingerprint" => grafanaAccepted
                ? grafanaFingerprint
                : _directResult["grafana_fingerprint"],
            "grafana_alert_pending" => grafanaAlertPending,
            "accepted_at" => _directResult["accepted_at"],
            "tracking_expires_at" => _directResult["tracking_expires_at"],
            "next_location_sequence" => _directResult["next_location_sequence"],
            "last_location_hex" => _directResult["last_location_hex"],
            "last_location_queued_at" => _directResult["last_location_queued_at"],
            "capture_stage" => _directResult["capture_stage"],
            "pending_location_hex" => _directResult["pending_location_hex"],
            "pending_location_pushover" => _directResult["pending_location_pushover"],
            "pending_location_grafana" => pendingGrafana
        });
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

    function onPosition(info as Position.Info) as Void {
        if (!_visible) {
            return;
        }
        if (_directResult != null) {
            onDirectPosition(info);
            return;
        }
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

    function onDirectPosition(info) {
        if (_directResult == null || _directResult["capture_stage"] == 3) {
            return;
        }
        var now = currentTime();
        if (now == null) {
            return;
        }
        if (now >= _directResult["tracking_expires_at"]) {
            expireDirectLocations();
            return;
        }
        if (_directResult["pending_location_hex"].length() > 0) {
            if (!_inFlight) {
                sendDirectLocation();
            }
            return;
        }
        if (_directResult["capture_stage"] == 1) {
            queueDirectLocation(info, 1, 2);
        } else if (_directResult["capture_stage"] == 2
            && shouldQueueDirectCadenceLocation(info, now)) {
            queueDirectLocation(info, 1, 2);
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
        return cadenceSecondsForExpiry(now, _activeIncident["expires_at"]);
    }

    function cadenceSecondsForExpiry(now, expiresAt) {
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
        WatchUi.requestUpdate();
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
            && PanicProtocol.stringEquals(head["kind"], PanicProtocol.V2_LOCATION_KIND)
            && PanicProtocol.stringEquals(
                head["incident_id"],
                _activeIncident["incident_id"]
            )
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

    function onStatusResponse(
        responseCode as Lang.Number,
        data as Lang.Dictionary or Lang.String or PersistedContent.Iterator or Null,
        requestId as Lang.Object
    ) as Void {
        if (!_inFlight
            || _statusQuery == null
            || !PanicProtocol.stringEquals(requestId, _statusQuery["request_id"])) {
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
            || !PanicProtocol.stringEquals(
                _activeIncident["incident_id"],
                query["incident_id"]
            )
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
        if (PanicProtocol.stringEquals(data["state"], "resolved")
            || PanicProtocol.stringEquals(data["state"], "expired")) {
            finishIncidentFromStatus(query, data["state"]);
            return;
        }
        if (receiveAt >= query["expires_at"]) {
            expireLocations();
            return;
        }
        setState(PanicProtocol.stringEquals(data["state"], "acknowledged")
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
                || !PanicProtocol.stringEquals(
                    _queue[i]["incident_id"],
                    query["incident_id"]
                )) {
                remaining.add(_queue[i]);
            }
        }
        stopLocations();
        refreshConfiguredMode();
        try {
            _retryTimer.stop();
        } catch (error) {
        }
        if (!persistState(remaining, null)) {
            setState("RESULT UNKNOWN", "Verified terminal status; local disarm was not saved");
            return;
        }
        _retryCount = 0;
        setState(PanicProtocol.stringEquals(state, "resolved")
                ? "INCIDENT RESOLVED"
                : "INCIDENT EXPIRED",
            _personalLive
                ? "Signed status verified; hold top button 2.5 seconds"
                : "Signed relay status verified; top button sends TEST");
        scheduleIdleCoverRefresh();
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
        if (PanicProtocol.stringEquals(recordHex, _activeIncident["last_location_hex"])) {
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
            setState("SETUP REQUIRED", "LIVE build secrets are not provisioned");
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
        if (_inFlight || _wifiCheckPending || _queue.size() == 0) {
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
        if (event["v"] == 1
            && _queue.size() == 1
            && _activeIncident == null
            && hasDirectAlertConfiguration()) {
            if (hasDirectGrafanaConfiguration()) {
                sendDirectGrafanaInitial(event);
            } else {
                sendDirectPushover(event, now);
            }
            return;
        }
        if (!PanicProtocol.isHttpsBaseUrl(baseUrl)
            || !PanicProtocol.stringEquals(event["device_id"], deviceId)
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
        _requestProviderFingerprint = null;
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

    function sendDirectPushover(event, now) {
        var providerFingerprint = DirectPushoverAdapter.configurationFingerprint();
        if (!PanicProtocol.isCanonicalDigest(providerFingerprint)) {
            handleFailure("configuration_failure", "Pushover configuration changed");
            return;
        }
        _requestEventId = event["event_id"];
        _requestProviderFingerprint = providerFingerprint;
        _inFlight = true;
        _displayEventId = event["event_id"];
        setState("SENDING TEST", connectionSummary());
        var parameters = DirectPushoverAdapter.initialParameters(event, now);
        var options = {
            :method => Communications.HTTP_REQUEST_METHOD_POST,
            :headers => {
                "Content-Type" => Communications.REQUEST_CONTENT_TYPE_URL_ENCODED
            },
            :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON,
            :context => event["event_id"]
        };
        try {
            Communications.makeWebRequest(
                DirectPushoverAdapter.ENDPOINT,
                parameters,
                options,
                method(:onPushoverResponse)
            );
        } catch (error) {
            _inFlight = false;
            _requestEventId = null;
            _requestProviderFingerprint = null;
            if (hasDirectGrafanaConfiguration() && !_directGrafanaRetryBlocked) {
                sendDirectGrafanaInitial(event);
            } else {
                handleFailure("retryable_failure", "Pushover request could not be queued");
            }
        }
    }

    function grafanaAlertPayload(eventId) {
        return DirectGrafanaAdapter.initialPayload(eventId);
    }

    function sendDirectGrafanaInitial(event) {
        sendDirectGrafanaRequest(event["event_id"]);
    }

    function sendDirectGrafanaAlert() {
        if (_directResult == null
            || !_directResult["grafana_alert_pending"]
            || _directGrafanaRetryBlocked) {
            return;
        }
        sendDirectGrafanaRequest(_directResult["event_id"]);
    }

    function sendDirectGrafanaRequest(eventId) {
        if (_inFlight || !hasDirectGrafanaConfiguration()) {
            return;
        }
        var providerFingerprint = DirectGrafanaAdapter.configurationFingerprint();
        if (!PanicProtocol.isCanonicalDigest(providerFingerprint)) {
            handleFailure("configuration_failure", "Grafana configuration changed");
            return;
        }
        var requestContext = eventId + "-grafana-alert";
        _requestEventId = requestContext;
        _requestProviderFingerprint = providerFingerprint;
        _inFlight = true;
        _displayEventId = eventId;
        setState("SENDING GRAFANA", connectionSummary());
        var options = {
            :method => Communications.HTTP_REQUEST_METHOD_POST,
            :headers => {
                "Content-Type" => Communications.REQUEST_CONTENT_TYPE_JSON
            },
            :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON,
            :context => requestContext
        };
        try {
            Communications.makeWebRequest(
                DirectGrafanaAdapter.endpoint(),
                grafanaAlertPayload(eventId),
                options,
                method(:onGrafanaAlertResponse)
            );
        } catch (error) {
            _inFlight = false;
            _requestEventId = null;
            _requestProviderFingerprint = null;
            if (_directResult != null) {
                _directGrafanaRetryBlocked = true;
                resumeDirectLocations();
            } else if (hasDirectPushoverConfiguration()) {
                _directGrafanaRetryBlocked = true;
                var now = currentTime();
                if (now != null && _queue.size() > 0) {
                    sendDirectPushover(_queue[0], now);
                }
            } else {
                handleFailure("retryable_failure", "Grafana request could not be queued");
            }
        }
    }

    function onGrafanaAlertResponse(
        responseCode as Lang.Number,
        data as Lang.Dictionary or Lang.String or PersistedContent.Iterator or Null,
        requestContext as Lang.Object
    ) as Void {
        if (!_inFlight
            || _statusQuery != null
            || !PanicProtocol.stringEquals(requestContext, _requestEventId)) {
            return;
        }
        var providerFingerprint = _requestProviderFingerprint;
        _inFlight = false;
        _requestEventId = null;
        _requestProviderFingerprint = null;
        if (responseCode >= 200 && responseCode < 300) {
            if (!PanicProtocol.isCanonicalDigest(providerFingerprint)) {
                setState("RESULT UNKNOWN", "Grafana accepted; route binding was lost");
                return;
            }
            var acceptedFingerprint = providerFingerprint as Lang.String;
            if (_directResult != null) {
                if (!persistDirectProviderState(true, acceptedFingerprint, false)) {
                    setState("RESULT UNKNOWN", "Grafana accepted; local evidence failed");
                    return;
                }
                _directGrafanaRetryBlocked = false;
                setState("PROVIDERS ACCEPTED", "Human acknowledgement remains separate");
                resumeDirectLocations();
                return;
            }
            if (_queue.size() == 0) {
                setState("RESULT UNKNOWN", "Persistent queue changed during Grafana request");
                return;
            }
            var event = _queue[0];
            if (!beginAcceptedDirectTracking(
                    event,
                    "",
                    "",
                    false,
                    "",
                    true,
                    acceptedFingerprint,
                    false
                )) {
                return;
            }
            return;
        }
        if (_directResult != null) {
            _directGrafanaRetryBlocked = true;
            setState(responseCode >= 400 && responseCode < 500 && responseCode != 429
                    ? "GRAFANA CONFIG ERROR"
                    : "GRAFANA PENDING",
                "Pushover accepted; Grafana will retry after reopen");
            resumeDirectLocations();
            return;
        }
        if (_queue.size() == 0) {
            setState("RESULT UNKNOWN", "Persistent queue changed during Grafana request");
            return;
        }
        var pending = _queue[0];
        if (responseCode < 0 && beginWifiFallback(pending, responseCode)) {
            return;
        }
        if (hasDirectPushoverConfiguration()) {
            _directGrafanaRetryBlocked = true;
            var now = currentTime();
            if (now != null) {
                sendDirectPushover(pending, now);
                return;
            }
        }
        handleFailure(responseCode >= 400 && responseCode < 500 && responseCode != 429
                ? "configuration_failure"
                : (responseCode < 0
                    ? transportFailure(responseCode)
                    : (responseCode == 429 ? "retryable_failure" : "result_unknown")),
            "Grafana result unknown; pending TEST retained");
    }

    function beginAcceptedDirectTracking(
        event,
        request,
        receipt,
        pushoverAccepted,
        pushoverFingerprint,
        grafanaAccepted,
        grafanaFingerprint,
        grafanaAlertPending
    ) {
        var acceptedAt = currentTime();
        var trackingExpiresAt = 0;
        var captureStage = 3;
        if (acceptedAt != null
            && acceptedAt <= PanicProtocol.MAX_TIME - LIVE_EXPIRY_SECONDS) {
            trackingExpiresAt = acceptedAt + LIVE_EXPIRY_SECONDS;
            captureStage = 0;
        }
        var directResult = {
            "event_id" => event["event_id"],
            "request" => request,
            "receipt" => receipt,
            "pushover_accepted" => pushoverAccepted,
            "pushover_fingerprint" => pushoverFingerprint,
            "grafana_accepted" => grafanaAccepted,
            "grafana_fingerprint" => grafanaFingerprint,
            "grafana_alert_pending" => grafanaAlertPending,
            "accepted_at" => acceptedAt == null ? 0 : acceptedAt,
            "tracking_expires_at" => trackingExpiresAt,
            "next_location_sequence" => 1,
            "last_location_hex" => "",
            "last_location_queued_at" => 0,
            "capture_stage" => captureStage,
            "pending_location_hex" => "",
            "pending_location_pushover" => false,
            "pending_location_grafana" => false
        };
        if (!persistStateWithDirect(copyQueue(1), _activeIncident, directResult)) {
            setState("RESULT UNKNOWN", "Provider accepted; local evidence failed");
            return false;
        }
        _retryCount = 0;
        setState("PROVIDER ACCEPTED", "Human acknowledgement remains separate");
        confirmProviderAcceptance();
        scheduleIdleCoverRefresh();
        if (grafanaAlertPending) {
            sendDirectGrafanaAlert();
        }
        if (captureStage == 0) {
            captureDirectLocations();
        }
        return true;
    }

    function onPushoverResponse(
        responseCode as Lang.Number,
        data as Lang.Dictionary or Lang.String or PersistedContent.Iterator or Null,
        eventId as Lang.Object
    ) as Void {
        if (!_inFlight
            || _statusQuery != null
            || !PanicProtocol.stringEquals(eventId, _requestEventId)) {
            return;
        }
        var providerFingerprint = _requestProviderFingerprint;
        _inFlight = false;
        if (_queue.size() == 0
            || !PanicProtocol.stringEquals(_queue[0]["event_id"], _requestEventId)) {
            _requestEventId = null;
            _requestProviderFingerprint = null;
            setState("RESULT UNKNOWN", "Persistent queue changed during Pushover request");
            return;
        }
        var event = _queue[0];
        _requestEventId = null;
        _requestProviderFingerprint = null;
        if (responseCode == 200 && isPushoverAcceptance(data)) {
            if (!PanicProtocol.isCanonicalDigest(providerFingerprint)) {
                setState("RESULT UNKNOWN", "Pushover accepted; route binding was lost");
                return;
            }
            beginAcceptedDirectTracking(
                event,
                data["request"],
                data["receipt"],
                true,
                providerFingerprint as Lang.String,
                false,
                "",
                hasDirectGrafanaConfiguration()
            );
            return;
        }
        if (responseCode < 0 && beginWifiFallback(event, responseCode)) {
            return;
        }
        if (hasDirectGrafanaConfiguration() && !_directGrafanaRetryBlocked) {
            sendDirectGrafanaInitial(event);
            return;
        }
        if (data instanceof Lang.Dictionary && data["status"] == 0) {
            handleFailure("configuration_failure", "Pushover rejected TEST configuration");
        } else if (responseCode >= 400 && responseCode < 500) {
            handleFailure("configuration_failure", "Pushover rejected TEST request");
        } else {
            handleFailure(responseCode < 0
                    ? transportFailure(responseCode)
                    : "result_unknown",
                "Pushover result unknown; pending TEST retained");
        }
    }

    function isPushoverAcceptance(data) {
        return data instanceof Lang.Dictionary
            && data["status"] == 1
            && isProviderReference(data["request"])
            && DirectPushoverAdapter.isToken(data["receipt"]);
    }

    function confirmProviderAcceptance() {
        try {
            if (Attention has :vibrate) {
                Attention.vibrate([
                    new Attention.VibeProfile(25, 120),
                    new Attention.VibeProfile(0, 80),
                    new Attention.VibeProfile(25, 120)
                ]);
            }
        } catch (error) {
            // Provider acceptance is already durable; feedback remains best-effort.
        }
    }

    function sendDirectLocation() {
        if (_inFlight
            || !_visible
            || _directResult == null
            || _directResult["capture_stage"] == 3
            || _directResult["pending_location_hex"].length() == 0
            || _directLocationRetryBlocked) {
            return;
        }
        if (_directResult["grafana_alert_pending"]
            && !_directGrafanaRetryBlocked
            && hasDirectGrafanaConfiguration()) {
            sendDirectGrafanaAlert();
            return;
        }
        var now = currentTime();
        if (now == null) {
            return;
        }
        if (now >= _directResult["tracking_expires_at"]) {
            expireDirectLocations();
            return;
        }
        var sendPushover = _directResult["pending_location_pushover"]
            && hasBoundDirectPushover();
        var sendGrafana = _directResult["pending_location_grafana"]
            && hasBoundDirectGrafana();
        if (!sendPushover && !sendGrafana) {
            _directLocationRetryBlocked = true;
            setState("ROUTE CHANGED", "Restore the accepted provider settings for GPS");
            return;
        }
        var record = PanicProtocol.hexBytes(
            _directResult["pending_location_hex"]
        );
        var captureAt = record.decodeNumber(Lang.NUMBER_FORMAT_UINT32, {
            :offset => 2,
            :endianness => Lang.ENDIAN_BIG
        });
        var path = record[15];
        var ageSeconds = DirectAlertSafety.captureAgeSeconds(captureAt, now);
        var latitude = record.decodeNumber(Lang.NUMBER_FORMAT_SINT32, {
            :offset => 6,
            :endianness => Lang.ENDIAN_BIG
        });
        var longitude = record.decodeNumber(Lang.NUMBER_FORMAT_SINT32, {
            :offset => 10,
            :endianness => Lang.ENDIAN_BIG
        });
        if (captureAt == 0
            || (path != 0 && path != 1)
            || ageSeconds < 0
            || latitude < -900000000
            || latitude > 900000000
            || longitude < -1800000000
            || longitude > 1800000000) {
            return;
        }
        var sequence = _directResult["next_location_sequence"];
        var latitudeText = coordinateE7Text(latitude);
        var longitudeText = coordinateE7Text(longitude);
        var requestContext = _directResult["event_id"]
            + "-location-" + sequence.format("%d");
        var mapUrl = "https://maps.google.com/?q="
            + latitudeText + "," + longitudeText;
        _requestEventId = requestContext;
        _inFlight = true;
        if (sendPushover) {
            var parameters = DirectPushoverAdapter.locationParameters(
                sequence,
                captureAt,
                path,
                ageSeconds,
                mapUrl
            );
            var pushoverOptions = {
                :method => Communications.HTTP_REQUEST_METHOD_POST,
                :headers => {
                    "Content-Type" => Communications.REQUEST_CONTENT_TYPE_URL_ENCODED
                },
                :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON,
                :context => requestContext
            };
            try {
                Communications.makeWebRequest(
                    DirectPushoverAdapter.ENDPOINT,
                    parameters,
                    pushoverOptions,
                    method(:onDirectLocationResponse)
                );
            } catch (error) {
                _inFlight = false;
                _requestEventId = null;
                scheduleDirectLocationRetry(true);
            }
            return;
        }
        if (sendGrafana) {
            var grafanaParameters = DirectGrafanaAdapter.locationPayload(
                _directResult["event_id"],
                sequence,
                captureAt,
                path,
                ageSeconds,
                mapUrl
            );
            var grafanaOptions = {
                :method => Communications.HTTP_REQUEST_METHOD_POST,
                :headers => {
                    "Content-Type" => Communications.REQUEST_CONTENT_TYPE_JSON
                },
                :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON,
                :context => requestContext
            };
            try {
                Communications.makeWebRequest(
                    DirectGrafanaAdapter.endpoint(),
                    grafanaParameters,
                    grafanaOptions,
                    method(:onGrafanaLocationResponse)
                );
            } catch (error) {
                _inFlight = false;
                _requestEventId = null;
                scheduleDirectLocationRetry(false);
            }
            return;
        }
        _inFlight = false;
        _requestEventId = null;
        _directLocationRetryBlocked = true;
    }

    function coordinateE7Text(value) {
        var negative = value < 0;
        var magnitude = negative ? -value : value;
        return (negative ? "-" : "")
            + (magnitude / 10000000).format("%d")
            + "." + (magnitude % 10000000).format("%07d");
    }

    function onDirectLocationResponse(
        responseCode as Lang.Number,
        data as Lang.Dictionary or Lang.String or PersistedContent.Iterator or Null,
        requestContext as Lang.Object
    ) as Void {
        if (!_inFlight
            || _statusQuery != null
            || !PanicProtocol.stringEquals(requestContext, _requestEventId)) {
            return;
        }
        _inFlight = false;
        _requestEventId = null;
        if (_directResult == null
            || _directResult["pending_location_hex"].length() == 0) {
            return;
        }
        if (responseCode == 200 && isPushoverMessageAcceptance(data)) {
            completeDirectLocationProvider(true);
            return;
        }
        if (data instanceof Lang.Dictionary && data["status"] == 0) {
            rejectDirectLocationProvider(true);
            return;
        }
        if (responseCode >= 400 && responseCode < 500) {
            rejectDirectLocationProvider(true);
            return;
        }
        scheduleDirectLocationRetry(true);
    }

    function onGrafanaLocationResponse(
        responseCode as Lang.Number,
        data as Lang.Dictionary or Lang.String or PersistedContent.Iterator or Null,
        requestContext as Lang.Object
    ) as Void {
        if (!_inFlight
            || _statusQuery != null
            || !PanicProtocol.stringEquals(requestContext, _requestEventId)) {
            return;
        }
        _inFlight = false;
        _requestEventId = null;
        if (_directResult == null
            || _directResult["pending_location_hex"].length() == 0) {
            return;
        }
        if (responseCode >= 200 && responseCode < 300) {
            completeDirectLocationProvider(false);
        } else if (responseCode == 429) {
            scheduleDirectLocationRetry(false);
        } else if (responseCode >= 400 && responseCode < 500) {
            rejectDirectLocationProvider(false);
        } else {
            scheduleDirectLocationRetry(false);
        }
    }

    function completeDirectLocationProvider(pushover) {
        if (_directResult == null
            || _directResult["pending_location_hex"].length() == 0) {
            return;
        }
        var pendingPushover = _directResult["pending_location_pushover"];
        var pendingGrafana = _directResult["pending_location_grafana"];
        if (pushover) {
            pendingPushover = false;
        } else {
            pendingGrafana = false;
        }
        pendingPushover = pendingPushover && hasBoundDirectPushover();
        pendingGrafana = pendingGrafana && hasBoundDirectGrafana();
        var recordHex = _directResult["pending_location_hex"];
        if (pendingPushover || pendingGrafana) {
            if (!persistDirectTracking(
                    _directResult["next_location_sequence"],
                    _directResult["last_location_hex"],
                    _directResult["last_location_queued_at"],
                    _directResult["capture_stage"],
                    recordHex,
                    pendingPushover,
                    pendingGrafana
                )) {
                _directLocationRetryBlocked = true;
                setState("LOCATION STATE UNSAVED", "Reopen to retry local GPS state");
                return;
            }
            _retryCount = 0;
            sendDirectLocation();
            return;
        }
        var record = PanicProtocol.hexBytes(recordHex);
        var captureAt = record.decodeNumber(Lang.NUMBER_FORMAT_UINT32, {
            :offset => 2,
            :endianness => Lang.ENDIAN_BIG
        });
        if (!persistDirectTracking(
                _directResult["next_location_sequence"] + 1,
                recordHex,
                captureAt,
                _directResult["capture_stage"],
                "",
                false,
                false
            )) {
            _directLocationRetryBlocked = true;
            setState("LOCATION STATE UNSAVED", "Reopen to retry local GPS state");
            return;
        }
        _retryCount = 0;
        _directLocationRetryBlocked = false;
        scheduleIdleCoverRefresh();
    }

    function rejectDirectLocationProvider(pushover) {
        // A definite provider rejection is terminal for that target. Advance
        // the single persisted fix just like a successful terminal outcome so
        // it cannot block later fixes to another still-bound route.
        completeDirectLocationProvider(pushover);
    }

    function isPushoverMessageAcceptance(data) {
        return data instanceof Lang.Dictionary
            && data["status"] == 1
            && isProviderReference(data["request"]);
    }

    function scheduleDirectLocationRetry(pushover) {
        if (!_visible
            || _directResult == null
            || _directResult["capture_stage"] == 3
            || _directResult["pending_location_hex"].length() == 0) {
            return;
        }
        if (_retryCount >= MAX_INITIAL_RETRIES) {
            rejectDirectLocationProvider(pushover);
            return;
        }
        _retryCount += 1;
        try {
            _retryTimer.stop();
        } catch (error) {
        }
        try {
            _retryTimer.start(
                method(:retryDirectLocation),
                RETRY_DELAY_MS,
                false
            );
        } catch (error) {
            // The durable pending fix is retried when the app is reopened.
        }
    }

    function retryDirectLocation() {
        if (!_inFlight
            && _directResult != null
            && _directResult["pending_location_hex"].length() > 0) {
            sendDirectLocation();
        }
    }

    function onResponse(
        responseCode as Lang.Number,
        data as Lang.Dictionary or Lang.String or PersistedContent.Iterator or Null,
        eventId as Lang.Object
    ) as Void {
        if (!_inFlight
            || _statusQuery != null
            || !PanicProtocol.stringEquals(eventId, _requestEventId)) {
            return;
        }
        _inFlight = false;
        if (_queue.size() == 0
            || !PanicProtocol.stringEquals(_queue[0]["event_id"], _requestEventId)) {
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
        if (responseCode < 0 && beginWifiFallback(event, responseCode)) {
            return;
        }
        handleFailure(result, "Pending event retained");
    }

    function beginWifiFallback(event, responseCode) {
        if ((responseCode != Communications.BLE_CONNECTION_UNAVAILABLE
                && responseCode != Communications.BLE_HOST_TIMEOUT)
            || _wifiCheckPending
            || PanicProtocol.stringEquals(_wifiFallbackEventId, event["event_id"])
            || !(Communications has :checkWifiConnection)) {
            return false;
        }
        _wifiFallbackEventId = event["event_id"];
        _wifiCheckPending = true;
        setState("TRYING WI-FI", "Phone unavailable; pending event retained");
        try {
            _retryTimer.stop();
        } catch (error) {
        }
        try {
            _retryTimer.start(method(:wifiCheckTimedOut), WIFI_CHECK_TIMEOUT_MS, false);
            Communications.checkWifiConnection(method(:onWifiConnectionChecked));
        } catch (error) {
            try {
                _retryTimer.stop();
            } catch (stopError) {
            }
            _wifiCheckPending = false;
            return false;
        }
        return true;
    }

    function onWifiConnectionChecked(
        result as {
            :wifiAvailable as Lang.Boolean,
            :errorCode as Communications.WifiConnectionStatus
        }
    ) as Void {
        if (!_wifiCheckPending) {
            return;
        }
        try {
            _retryTimer.stop();
        } catch (error) {
        }
        _wifiCheckPending = false;
        if (_queue.size() == 0
            || !PanicProtocol.stringEquals(
                _queue[0]["event_id"],
                _wifiFallbackEventId
            )) {
            return;
        }
        var wifiAvailable = false;
        try {
            wifiAvailable = result[:wifiAvailable] == true;
        } catch (error) {
            wifiAvailable = false;
        }
        if (wifiAvailable) {
            setState("RETRYING WI-FI", hasDirectAlertConfiguration()
                ? "Pending until provider acceptance"
                : "Pending until signed relay acceptance");
            sendPending();
        } else {
            handleFailure("retryable_failure", "Wi-Fi unavailable; pending event retained");
        }
    }

    function wifiCheckTimedOut() {
        if (!_wifiCheckPending) {
            return;
        }
        _wifiCheckPending = false;
        handleFailure("retryable_failure", "Wi-Fi check timed out; pending event retained");
    }

    function handleFailure(result, detail) {
        var testPending = _queue.size() > 0 && _queue[0]["v"] == 1;
        if (PanicProtocol.stringEquals(result, "configuration_failure")) {
            setState(testPending ? "TEST CONFIG ERROR" : "CONFIGURATION FAILURE", detail);
            return;
        }
        if (testPending) {
            setState("TEST PENDING", detail);
        } else if (PanicProtocol.stringEquals(result, "retryable_failure")) {
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
                setState(testPending ? "TEST PENDING" : "RETRYABLE FAILURE",
                    testPending
                        ? "Automatic retry unavailable; press top button"
                        : "Top button retries immutable event");
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
            || responseCode == Communications.BLE_CONNECTION_UNAVAILABLE
            || responseCode == Communications.BLE_HOST_TIMEOUT) {
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
            var lte = connections[:lte];
            if (lte != null && lte.state == System.CONNECTION_STATE_CONNECTED) {
                return "LTE reported connected; CIQ web route unclaimed";
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
        return persistStateWithDirect(nextQueue, nextActive, _directResult);
    }

    function persistStateWithDirect(nextQueue, nextActive, nextDirectResult) {
        try {
            Storage.setValue(STATE_KEY, {
                "queue" => nextQueue,
                "active" => nextActive,
                "direct_result" => nextDirectResult
            });
            _queue = nextQueue;
            _activeIncident = nextActive;
            _directResult = nextDirectResult;
            if (_queue.size() > 0) {
                _displayEventId = _queue[0]["event_id"];
            } else if (_activeIncident != null) {
                _displayEventId = _activeIncident["incident_id"];
            } else if (_directResult != null) {
                _displayEventId = _directResult["event_id"];
            } else {
                _displayEventId = null;
            }
            return true;
        } catch (error) {
            return false;
        }
    }

    function resetAcceptedTest() {
        stopLocations();
        if (persistStateWithDirect([], _activeIncident, null)) {
            _acceptedStatusVisible = false;
            _acceptedActionFeedback = null;
            _state = "READY — TEST";
            _detail = "Hold top button 2.5 seconds";
            selectStartupMode();
            WatchUi.requestUpdate();
        } else {
            _acceptedStatusVisible = false;
            _acceptedActionFeedback = null;
            setState("CONFIGURATION FAILURE", "Cannot reset accepted TEST");
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
                if (nextActive != null
                    && PanicProtocol.stringEquals(nextActive["incident_id"], incidentId)) {
                    nextActive = null;
                }
                try {
                    _retryTimer.stop();
                } catch (error) {
                }
                if (persistState(remaining, nextActive)) {
                    refreshConfiguredMode();
                    _retryCount = 0;
                    setState("RESULT UNKNOWN — EXPIRED", "Expired LIVE removed explicitly");
                } else {
                    setState("CONFIGURATION FAILURE", "Cannot remove expired LIVE");
                }
                return true;
            }
            if (persistState([], _activeIncident)) {
                _state = "READY — TEST";
                _detail = "Pending TEST abandoned explicitly";
                selectStartupMode();
                WatchUi.requestUpdate();
            } else {
                setState("CONFIGURATION FAILURE", "Cannot abandon pending TEST");
            }
            return true;
        }
        if (_directResult != null) {
            if (!_acceptedStatusVisible) {
                toggleAcceptedStatus();
                return true;
            }
            return beginAcceptedActionFeedback("RESET");
        }
        _state = "READY — TEST";
        _detail = "Hold top button 2.5 seconds";
        selectStartupMode();
        WatchUi.requestUpdate();
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
        return _view.selectAction();
    }

    function onNextPage() {
        return _view.downAction();
    }

    function onKeyPressed(event) {
        var key = event.getKey();
        if (key == WatchUi.KEY_START || key == WatchUi.KEY_ENTER) {
            return _view.startActionPressed();
        }
        return false;
    }

    function onKeyReleased(event) {
        var key = event.getKey();
        if (key == WatchUi.KEY_START || key == WatchUi.KEY_ENTER) {
            return _view.startActionReleased();
        }
        return false;
    }

    function onHold(event) {
        return true;
    }

    function onRelease(event) {
        return true;
    }

    function onMenu() {
        return _view.menuAction();
    }
}

(:test)
function directValidRestartStateRoundTrips(logger) {
    var eventId = "AAECAwQFBgcICQoLDA0ODw";
    var grafanaFingerprint = DirectGrafanaAdapter.configurationFingerprintFor(
        "https://oncall-prod-eu-west-0.grafana.net/oncall/"
        + "integrations/v1/formatted_webhook/"
        + "AbCdEfGhIjKlMnOpQrStUvWxYz012345/"
    );
    var locationHex = "01020000006400000000000000000200";
    Storage.setValue("event_state_v2", {
        "queue" => [],
        "active" => null,
        "direct_result" => {
            "event_id" => eventId,
            "request" => "",
            "receipt" => "",
            "pushover_accepted" => false,
            "pushover_fingerprint" => "",
            "grafana_accepted" => true,
            "grafana_fingerprint" => grafanaFingerprint,
            "grafana_alert_pending" => false,
            "accepted_at" => 100,
            "tracking_expires_at" => 3700,
            "next_location_sequence" => 2,
            "last_location_hex" => locationHex,
            "last_location_queued_at" => 100,
            "capture_stage" => 1,
            "pending_location_hex" => "",
            "pending_location_pushover" => false,
            "pending_location_grafana" => false
        }
    });

    var reloaded = new PanicView();
    var survived = PanicProtocol.stringEquals(reloaded._state, "PROVIDER ACCEPTED")
        && reloaded._directResult != null
        && PanicProtocol.stringEquals(reloaded._directResult["event_id"], eventId);
    var coverProtected = survived && reloaded.shouldShowCover();
    reloaded.downAction();
    var detailsRevealed = reloaded._acceptedStatusVisible
        && !reloaded.shouldShowCover()
        && reloaded._directResult != null;
    reloaded.menuAction();
    var resetFeedback = PanicProtocol.stringEquals(
        reloaded._acceptedActionFeedback, "RESET");
    reloaded.completeAcceptedActionFeedback();
    var storedAfterReset = Storage.getValue("event_state_v2");
    var reset = reloaded._directResult == null
        && !reloaded._acceptedStatusVisible
        && PanicProtocol.hasExactKeys(storedAfterReset, ["queue", "active", "direct_result"])
        && (storedAfterReset as Lang.Dictionary)["direct_result"] == null;
    Storage.deleteValue("event_state_v2");
    if (!survived) {
        logger.error("Valid Grafana direct state did not survive storage roundtrip");
        return false;
    }
    if (!coverProtected || !detailsRevealed || !resetFeedback || !reset) {
        logger.error("Accepted direct TEST could not reveal details and reset safely");
        return false;
    }
    return true;
}

(:test)
function directInvalidRestartStateRecovers(logger) {
    Storage.setValue("event_state_v2", {
        "queue" => [],
        "active" => null,
        "direct_result" => {
            "event_id" => "AAECAwQFBgcICQoLDA0ODw"
        }
    });

    var reloaded = new PanicView();
    var recovered = !PanicProtocol.stringEquals(
            reloaded._state,
            "CONFIGURATION FAILURE"
        )
        && reloaded._directResult == null
        && Storage.getValue("event_state_v2") == null;
    Storage.deleteValue("event_state_v2");
    if (!recovered) {
        logger.error("Invalid direct TEST state locked the app after restart");
        return false;
    }
    return true;
}

(:test)
function directInvalidQueuedStateFailsClosed(logger) {
    var event = PanicProtocol.newTestEvent(
        "AAECAwQFBgcICQoLDA0ODw",
        "EBESExQVFhcYGRobHB0eHw",
        1788105600
    );
    Storage.setValue("event_state_v2", {
        "queue" => [event],
        "active" => null,
        "direct_result" => {
            "event_id" => "AAECAwQFBgcICQoLDA0ODw"
        }
    });

    var reloaded = new PanicView();
    var retained = PanicProtocol.stringEquals(
            reloaded._state,
            "CONFIGURATION FAILURE"
        )
        && Storage.getValue("event_state_v2") != null;
    Storage.deleteValue("event_state_v2");
    if (!retained) {
        logger.error("Invalid state with a pending event was auto-cleared");
        return false;
    }
    return true;
}
