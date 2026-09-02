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
    const LIVE_ARM_HOLD_MS = 1500;
    const COVER_REFRESH_MS = 60000;
    const RETRY_DELAY_MS = 5000;
    const WIFI_CHECK_TIMEOUT_MS = 10000;
    const PUSHOVER_URL = "https://api.pushover.net/1/messages.json";
    const DIRECT_TEST_MESSAGE =
        "KEIN ECHTER NOTFALL. Garmin Testausloesung; keine Hilfeleistung erforderlich.";
    const DIRECT_LOCATION_MESSAGE =
        "KEIN ECHTER NOTFALL. Aktueller Garmin GPS-Teststandort.";
    const DIRECT_TEST_TITLE = "TESTNOTRUF";
    const DIRECT_LOCATION_TITLE = "TESTNOTRUF — GPS";
    const PUSHOVER_TOKEN_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
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
    const DIRECT_RESULT_KEYS = [
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
    const ACTIVE_KEYS = [
        "incident_id",
        "expires_at",
        "next_sequence",
        "last_location_hex",
        "last_location_queued_at",
        "capture_stage"
    ];

    var _state = "READY — TEST";
    var _detail = "Top button sends TEST";
    var _queue = [];
    var _activeIncident = null;
    var _directResult = null;
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
    var _personalLive = false;
    var _armingLive = false;
    var _wifiCheckPending = false;
    var _wifiFallbackEventId = null;
    var _testStartDown = false;
    var _directLocationRetryBlocked = false;
    var _directGrafanaRetryBlocked = false;

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
        cancelLiveArm();
        stopLocations();
        try {
            _retryTimer.stop();
        } catch (error) {
        }
        _wifiCheckPending = false;
    }

    function shouldShowCover() {
        return _directResult != null;
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
            _detail = "Press top button to send";
        } else if (_personalLive) {
            _state = "READY — LIVE";
            _detail = "Hold top button to trigger";
        } else if (hasRelayTestConfiguration()) {
            _detail = "Press top button to send TEST";
        } else {
            _state = "SETUP REQUIRED";
            _detail = "Enter Grafana webhook or Pushover keys";
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
        _detail = "Press top button to send";
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
            return isPushoverToken(Properties.getValue("pushoverUserKey"))
                && isPushoverToken(Properties.getValue("pushoverApiToken"));
        } catch (error) {
            return false;
        }
    }

    function hasDirectGrafanaConfiguration() {
        try {
            return PanicProtocol.isGrafanaWebhookUrl(
                Properties.getValue("grafanaWebhookUrl")
            );
        } catch (error) {
            return false;
        }
    }

    function hasDirectAlertConfiguration() {
        return hasDirectPushoverConfiguration() || hasDirectGrafanaConfiguration();
    }

    function isPushoverToken(value) {
        if (!(value instanceof Lang.String) || value.length() != 30) {
            return false;
        }
        var characters = value.toCharArray();
        for (var i = 0; i < characters.size(); i += 1) {
            if (PUSHOVER_TOKEN_ALPHABET.find(characters[i].toString()) == null) {
                return false;
            }
        }
        return true;
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
                var legacyDirect = storedState["direct_result"] as Lang.Dictionary;
                stored = {
                    "queue" => storedState["queue"],
                    "active" => storedState["active"],
                    "direct_result" => {
                        "event_id" => legacyDirect["event_id"],
                        "request" => legacyDirect["request"],
                        "receipt" => legacyDirect["receipt"],
                        "pushover_accepted" => true,
                        "grafana_accepted" => false,
                        "grafana_alert_pending" => false,
                        "tracking_expires_at" => 0,
                        "next_location_sequence" => 1,
                        "last_location_hex" => "",
                        "last_location_queued_at" => 0,
                        "capture_stage" => 3,
                        "pending_location_hex" => "",
                        "pending_location_pushover" => false,
                        "pending_location_grafana" => false
                    }
                };
                Storage.setValue(STATE_KEY, stored);
            }
            storedState = stored as Lang.Dictionary;
            if (PanicProtocol.hasExactKeys(storedState, STATE_KEYS)
                && storedState["direct_result"] != null
                && PanicProtocol.hasExactKeys(
                    storedState["direct_result"],
                    LEGACY_DIRECT_TRACKING_KEYS
                )) {
                var legacyTracking = storedState["direct_result"] as Lang.Dictionary;
                var hasPendingLocation = legacyTracking["pending_location_hex"].length() > 0;
                stored = {
                    "queue" => storedState["queue"],
                    "active" => storedState["active"],
                    "direct_result" => {
                        "event_id" => legacyTracking["event_id"],
                        "request" => legacyTracking["request"],
                        "receipt" => legacyTracking["receipt"],
                        "pushover_accepted" => true,
                        "grafana_accepted" => false,
                        "grafana_alert_pending" => false,
                        "tracking_expires_at" => legacyTracking["tracking_expires_at"],
                        "next_location_sequence" => legacyTracking["next_location_sequence"],
                        "last_location_hex" => legacyTracking["last_location_hex"],
                        "last_location_queued_at" => legacyTracking["last_location_queued_at"],
                        "capture_stage" => legacyTracking["capture_stage"],
                        "pending_location_hex" => legacyTracking["pending_location_hex"],
                        "pending_location_pushover" => hasPendingLocation,
                        "pending_location_grafana" => false
                    }
                };
                Storage.setValue(STATE_KEY, stored);
            }
            if (!validStoredState(stored)) {
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
        return value == null
            || (PanicProtocol.hasExactKeys(value, DIRECT_RESULT_KEYS)
                && PanicProtocol.isCanonicalId(value["event_id"])
                && value["pushover_accepted"] instanceof Lang.Boolean
                && value["grafana_accepted"] instanceof Lang.Boolean
                && value["grafana_alert_pending"] instanceof Lang.Boolean
                && (value["pushover_accepted"] || value["grafana_accepted"])
                && (!value["pushover_accepted"]
                    ? PanicProtocol.stringEquals(value["request"], "")
                        && PanicProtocol.stringEquals(value["receipt"], "")
                    : isProviderReference(value["request"])
                        && isPushoverToken(value["receipt"]))
                && (!value["grafana_accepted"] || !value["grafana_alert_pending"])
                && value["tracking_expires_at"] instanceof Lang.Number
                && value["tracking_expires_at"] >= 0
                && value["next_location_sequence"] instanceof Lang.Number
                && value["next_location_sequence"] >= 1
                && validLocationHex(value["last_location_hex"])
                && value["last_location_queued_at"] instanceof Lang.Number
                && value["last_location_queued_at"] >= 0
                && (value["tracking_expires_at"] == 0
                    ? value["last_location_queued_at"] == 0
                    : value["last_location_queued_at"]
                        <= value["tracking_expires_at"])
                && value["capture_stage"] instanceof Lang.Number
                && value["capture_stage"] >= 0
                && value["capture_stage"] <= 3
                && validLocationHex(value["pending_location_hex"])
                && value["pending_location_pushover"] instanceof Lang.Boolean
                && value["pending_location_grafana"] instanceof Lang.Boolean
                && (!value["pending_location_pushover"]
                    || value["pushover_accepted"])
                && (!value["pending_location_grafana"]
                    || value["grafana_accepted"])
                && (value["pending_location_hex"].length() == 0
                    ? !value["pending_location_pushover"]
                        && !value["pending_location_grafana"]
                    : value["pending_location_pushover"]
                        || value["pending_location_grafana"])
                && (value["capture_stage"] == 0
                    ? value["last_location_hex"].length() == 0
                        && value["last_location_queued_at"] == 0
                        && value["pending_location_hex"].length() == 0
                    : true)
                && (value["capture_stage"] == 3
                    ? value["last_location_hex"].length() == 0
                        && value["last_location_queued_at"] == 0
                        && value["pending_location_hex"].length() == 0
                    : true));
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

    function protectedPersonName() {
        var value = Properties.getValue("protectedPersonName");
        if (!(value instanceof Lang.String)
            || value.length() < 1
            || value.length() > 40) {
            return "";
        }
        return value as Lang.String;
    }

    function personalizedTestTitle(baseTitle) {
        var name = protectedPersonName();
        return name.length() > 0 ? baseTitle + " — " + name : baseTitle;
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
        if (!PanicProtocol.stringEquals(_mode, "LIVE")) {
            if (_testStartDown) {
                return true;
            }
            _testStartDown = true;
            activate();
            return true;
        }
        if (_armingLive
            || _inFlight
            || _queue.size() > 0
            || _activeIncident != null) {
            return true;
        }
        _armingLive = true;
        try {
            _retryTimer.stop();
        } catch (error) {
        }
        try {
            _retryTimer.start(method(:commitArmedLive), LIVE_ARM_HOLD_MS, false);
        } catch (error) {
            _armingLive = false;
        }
        return true;
    }

    function startActionReleased() {
        if (!PanicProtocol.stringEquals(_mode, "LIVE")) {
            _testStartDown = false;
            return true;
        }
        cancelLiveArm();
        return true;
    }

    function cancelLiveArm() {
        if (!_armingLive) {
            return;
        }
        _armingLive = false;
        try {
            _retryTimer.stop();
        } catch (error) {
        }
    }

    function commitArmedLive() {
        if (!_armingLive) {
            return;
        }
        _armingLive = false;
        if (!_visible
            || _inFlight
            || _queue.size() > 0
            || _activeIncident != null) {
            return;
        }
        activateLive();
    }

    function selectAction() {
        if (PanicProtocol.stringEquals(_mode, "LIVE")) {
            return true;
        }
        activate();
        return true;
    }

    function downAction() {
        return true;
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
            setState("SETUP REQUIRED", "Enter Grafana webhook or Pushover keys");
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
                : (_personalLive ? "Hold top button for a new LIVE incident"
                    : "Top button sends a non-sensitive TEST"));
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
                persistDirectTracking(
                    _directResult["next_location_sequence"],
                    _directResult["last_location_hex"],
                    _directResult["last_location_queued_at"],
                    1,
                    "",
                    false,
                    false
                );
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
        persistDirectTracking(
            _directResult["next_location_sequence"],
            "",
            0,
            3,
            "",
            false,
            false
        );
        scheduleIdleCoverRefresh();
    }

    function queueDirectLocation(info, path, nextCaptureStage) {
        if (_directResult == null
            || _directResult["capture_stage"] == 3
            || _directResult["pending_location_hex"].length() > 0
            || info == null
            || info.position == null
            || info.when == null
            || info.accuracy == Position.QUALITY_NOT_AVAILABLE) {
            return false;
        }
        var now = currentTime();
        if (now == null
            || now >= _directResult["tracking_expires_at"]
            || info.when.value() > now) {
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
        if (!persistDirectTracking(
                _directResult["next_location_sequence"],
                _directResult["last_location_hex"],
                _directResult["last_location_queued_at"],
                nextCaptureStage,
                recordHex,
                _directResult["pushover_accepted"],
                _directResult["grafana_accepted"]
            )) {
            return false;
        }
        sendDirectLocation();
        return true;
    }

    function shouldQueueDirectCadenceLocation(info, now) {
        if (_directResult == null
            || _directResult["pending_location_hex"].length() > 0
            || info == null
            || info.position == null
            || info.when == null
            || info.accuracy == Position.QUALITY_NOT_AVAILABLE
            || info.when.value() > now) {
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
            "grafana_accepted" => _directResult["grafana_accepted"],
            "grafana_alert_pending" => _directResult["grafana_alert_pending"],
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

    function persistDirectProviderState(grafanaAccepted, grafanaAlertPending) {
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
            "grafana_accepted" => grafanaAccepted,
            "grafana_alert_pending" => grafanaAlertPending,
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
                ? "Signed relay status verified; hold top button for a new incident"
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
            if (hasDirectPushoverConfiguration()) {
                sendDirectPushover(event, now);
            } else {
                sendDirectGrafanaInitial(event);
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
        _requestEventId = event["event_id"];
        _inFlight = true;
        _displayEventId = event["event_id"];
        setState("SENDING TEST", connectionSummary());
        var parameters = {
            "token" => Properties.getValue("pushoverApiToken"),
            "user" => Properties.getValue("pushoverUserKey"),
            "title" => personalizedTestTitle(DIRECT_TEST_TITLE),
            "message" => DIRECT_TEST_MESSAGE,
            "priority" => "2",
            "retry" => "30",
            "expire" => (event["expires_at"] - now).toString()
        };
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
                PUSHOVER_URL,
                parameters,
                options,
                method(:onPushoverResponse)
            );
        } catch (error) {
            _inFlight = false;
            _requestEventId = null;
            if (hasDirectGrafanaConfiguration()) {
                sendDirectGrafanaInitial(event);
            } else {
                handleFailure("retryable_failure", "Pushover request could not be queued");
            }
        }
    }

    function grafanaAlertPayload(eventId) {
        return {
            "alert_uid" => eventId,
            "title" => personalizedTestTitle(DIRECT_TEST_TITLE),
            "state" => "alerting",
            "message" => DIRECT_TEST_MESSAGE
        };
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
        var requestContext = eventId + "-grafana-alert";
        _requestEventId = requestContext;
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
                Properties.getValue("grafanaWebhookUrl"),
                grafanaAlertPayload(eventId),
                options,
                method(:onGrafanaAlertResponse)
            );
        } catch (error) {
            _inFlight = false;
            _requestEventId = null;
            if (_directResult != null) {
                _directGrafanaRetryBlocked = true;
                resumeDirectLocations();
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
        _inFlight = false;
        _requestEventId = null;
        if (responseCode >= 200 && responseCode < 300) {
            if (_directResult != null) {
                if (!persistDirectProviderState(true, false)) {
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
            if (!beginAcceptedDirectTracking(event, "", "", false, true, false)) {
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
        grafanaAccepted,
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
            "grafana_accepted" => grafanaAccepted,
            "grafana_alert_pending" => grafanaAlertPending,
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
        _inFlight = false;
        if (_queue.size() == 0
            || !PanicProtocol.stringEquals(_queue[0]["event_id"], _requestEventId)) {
            _requestEventId = null;
            setState("RESULT UNKNOWN", "Persistent queue changed during Pushover request");
            return;
        }
        var event = _queue[0];
        _requestEventId = null;
        if (responseCode == 200 && isPushoverAcceptance(data)) {
            beginAcceptedDirectTracking(
                event,
                data["request"],
                data["receipt"],
                true,
                false,
                hasDirectGrafanaConfiguration()
            );
            return;
        }
        if (responseCode < 0 && beginWifiFallback(event, responseCode)) {
            return;
        }
        if (hasDirectGrafanaConfiguration()) {
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
            && isPushoverToken(data["receipt"]);
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
        var record = PanicProtocol.hexBytes(
            _directResult["pending_location_hex"]
        );
        var captureAt = record.decodeNumber(Lang.NUMBER_FORMAT_UINT32, {
            :offset => 2,
            :endianness => Lang.ENDIAN_BIG
        });
        var latitude = record.decodeNumber(Lang.NUMBER_FORMAT_SINT32, {
            :offset => 6,
            :endianness => Lang.ENDIAN_BIG
        });
        var longitude = record.decodeNumber(Lang.NUMBER_FORMAT_SINT32, {
            :offset => 10,
            :endianness => Lang.ENDIAN_BIG
        });
        if (captureAt == 0
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
        if (_directResult["pending_location_pushover"]
            && hasDirectPushoverConfiguration()) {
            var parameters = {
                "token" => Properties.getValue("pushoverApiToken"),
                "user" => Properties.getValue("pushoverUserKey"),
                "title" => personalizedTestTitle(DIRECT_LOCATION_TITLE),
                "message" => DIRECT_LOCATION_MESSAGE
                    + " Update " + sequence.format("%d") + ".",
                "priority" => sequence == 1 ? "1" : "0",
                "timestamp" => captureAt.format("%d"),
                "url" => mapUrl,
                "url_title" => "Open current location"
            };
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
                    PUSHOVER_URL,
                    parameters,
                    pushoverOptions,
                    method(:onDirectLocationResponse)
                );
            } catch (error) {
                _inFlight = false;
                _requestEventId = null;
                scheduleDirectLocationRetry();
            }
            return;
        }
        if (_directResult["pending_location_grafana"]
            && hasDirectGrafanaConfiguration()) {
            var grafanaParameters = {
                "alert_uid" => _directResult["event_id"],
                "title" => personalizedTestTitle(DIRECT_LOCATION_TITLE),
                "state" => "alerting",
                "message" => DIRECT_LOCATION_MESSAGE
                    + " Update " + sequence.format("%d") + ". " + mapUrl,
                "link_to_upstream_details" => mapUrl
            };
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
                    Properties.getValue("grafanaWebhookUrl"),
                    grafanaParameters,
                    grafanaOptions,
                    method(:onGrafanaLocationResponse)
                );
            } catch (error) {
                _inFlight = false;
                _requestEventId = null;
                scheduleDirectLocationRetry();
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
        scheduleDirectLocationRetry();
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
            scheduleDirectLocationRetry();
        } else if (responseCode >= 400 && responseCode < 500) {
            rejectDirectLocationProvider(false);
        } else {
            scheduleDirectLocationRetry();
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
                scheduleDirectLocationRetry();
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
            scheduleDirectLocationRetry();
            return;
        }
        _retryCount = 0;
        _directLocationRetryBlocked = false;
        scheduleIdleCoverRefresh();
    }

    function rejectDirectLocationProvider(pushover) {
        if (_directResult == null) {
            return;
        }
        var pendingPushover = _directResult["pending_location_pushover"];
        var pendingGrafana = _directResult["pending_location_grafana"];
        if (pushover) {
            pendingPushover = false;
        } else {
            pendingGrafana = false;
        }
        if (!pendingPushover && !pendingGrafana) {
            _directLocationRetryBlocked = true;
            return;
        }
        if (!persistDirectTracking(
                _directResult["next_location_sequence"],
                _directResult["last_location_hex"],
                _directResult["last_location_queued_at"],
                _directResult["capture_stage"],
                _directResult["pending_location_hex"],
                pendingPushover,
                pendingGrafana
            )) {
            _directLocationRetryBlocked = true;
            return;
        }
        _retryCount = 0;
        sendDirectLocation();
    }

    function isPushoverMessageAcceptance(data) {
        return data instanceof Lang.Dictionary
            && data["status"] == 1
            && isProviderReference(data["request"]);
    }

    function scheduleDirectLocationRetry() {
        if (!_visible
            || _directResult == null
            || _directResult["capture_stage"] == 3
            || _directResult["pending_location_hex"].length() == 0) {
            return;
        }
        if (_retryCount >= MAX_INITIAL_RETRIES) {
            _directLocationRetryBlocked = true;
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
            stopLocations();
            if (persistStateWithDirect([], _activeIncident, null)) {
                _state = "READY — TEST";
                _detail = "Press top button to send";
                selectStartupMode();
                WatchUi.requestUpdate();
            } else {
                setState("CONFIGURATION FAILURE", "Cannot reset accepted TEST");
            }
            return true;
        }
        _state = "READY — TEST";
        _detail = "Press top button to send";
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

    function onMenu() {
        return _view.menuAction();
    }
}
