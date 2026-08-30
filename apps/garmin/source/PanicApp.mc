// SPDX-License-Identifier: MIT

import Toybox.Application;
import Toybox.Application.Properties;
import Toybox.Application.Storage;
import Toybox.Communications;
import Toybox.Graphics;
import Toybox.Time;
import Toybox.WatchUi;

class PanicApp extends Application.AppBase {
    function initialize() {
        AppBase.initialize();
    }

    function getInitialView() {
        var view = new PanicView();
        return [view, new PanicDelegate(view)];
    }
}

class PanicView extends WatchUi.View {
    const PENDING_KEY = "pending_event";

    var _state = "READY";
    var _detail = "Press START to send TEST";
    var _pendingEvent = null;
    var _activeKeyHex = null;
    var _displayEventId = null;
    var _inFlight = false;

    function initialize() {
        View.initialize();
        loadPending();
    }

    function loadPending() {
        try {
            var stored = Storage.getValue(PENDING_KEY);
            if (stored != null) {
                if (PanicProtocol.isEvent(stored)) {
                    _pendingEvent = stored;
                    _displayEventId = stored["event_id"];
                    _state = "RESULT UNKNOWN";
                    _detail = "START checks; MENU abandons TEST";
                } else {
                    _state = "CONFIGURATION FAILURE";
                    _detail = "Stored TEST event is invalid";
                }
            }
        } catch (error) {
            _state = "CONFIGURATION FAILURE";
            _detail = "Cannot read persistent storage";
        }
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

        var relayUrl = Properties.getValue("relayUrl");
        var deviceId = Properties.getValue("deviceId");
        var keyHex = Properties.getValue("hmacKeyHex");
        if (!PanicProtocol.isHttpsUrl(relayUrl)
            || !PanicProtocol.isCanonicalId(deviceId)
            || !PanicProtocol.isLowerHexKey(keyHex)) {
            setState("CONFIGURATION FAILURE", "Check URL, device ID, and key");
            return;
        }

        if (_pendingEvent == null) {
            var now;
            try {
                now = Time.getCurrentTime({
                    :currentTimeType => Time.CURRENT_TIME_RTC
                }).value();
            } catch (error) {
                setState("CONFIGURATION FAILURE", "Watch time is not trustworthy");
                return;
            }

            var eventId = PanicProtocol.randomId();
            var event = PanicProtocol.newEvent(eventId, deviceId, now);
            if (!PanicProtocol.isEvent(event)) {
                setState("CONFIGURATION FAILURE", "Watch time is outside v1 range");
                return;
            }
            try {
                // The immutable event is durable before any network call.
                Storage.setValue(PENDING_KEY, event);
                _pendingEvent = event;
                _displayEventId = eventId;
            } catch (error) {
                setState("CONFIGURATION FAILURE", "Cannot persist TEST event");
                return;
            }
        }

        if (!PanicProtocol.isEvent(_pendingEvent)
            || _pendingEvent["device_id"] != deviceId) {
            setState("CONFIGURATION FAILURE", "Pending TEST belongs to another device");
            return;
        }

        var requestSignature;
        try {
            requestSignature = PanicProtocol.signature(
                keyHex,
                PanicProtocol.submitSigningInput(_pendingEvent)
            );
        } catch (error) {
            setState("CONFIGURATION FAILURE", "Cannot sign TEST event");
            return;
        }

        _activeKeyHex = keyHex;
        _inFlight = true;
        setState("SENDING", "TEST event is in flight");

        var options = {
            :method => Communications.HTTP_REQUEST_METHOD_POST,
            :headers => {
                "Content-Type" => Communications.REQUEST_CONTENT_TYPE_JSON,
                "X-SPB-Signature" => requestSignature
            },
            :responseType => Communications.HTTP_RESPONSE_CONTENT_TYPE_JSON
        };

        try {
            Communications.makeWebRequest(
                relayUrl,
                _pendingEvent,
                options,
                method(:onResponse)
            );
        } catch (error) {
            _inFlight = false;
            _activeKeyHex = null;
            setState("RETRYABLE FAILURE", "Request could not be queued");
        }
    }

    function onResponse(responseCode, data) {
        if (!_inFlight) {
            return;
        }

        _inFlight = false;
        var eventId = _pendingEvent["event_id"];
        var keyHex = _activeKeyHex;
        _activeKeyHex = null;

        if (responseCode == 200) {
            var verified = false;
            try {
                verified = PanicProtocol.verifyAccepted(data, eventId, keyHex);
            } catch (error) {
                verified = false;
            }

            if (verified) {
                // Only an authenticated, exact success response reaches this state.
                try {
                    Storage.deleteValue(PENDING_KEY);
                } catch (error) {
                    // The relay is idempotent; retaining the durable event is safe.
                }
                _pendingEvent = null;
                setState("PROVIDER ACCEPTED", "Signed Pushover result verified");
            } else {
                setState("RESULT UNKNOWN", "START checks; MENU abandons TEST");
            }
            return;
        }

        var result = responseCode < 0
            ? transportFailure(responseCode)
            : PanicProtocol.failureResult(data);
        if (result == "retryable_failure") {
            setState("RETRYABLE FAILURE", "START retries; MENU abandons TEST");
        } else if (result == "configuration_failure") {
            setState("CONFIGURATION FAILURE", "Check relay and app settings");
        } else {
            setState("RESULT UNKNOWN", "START checks; MENU abandons TEST");
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

    function abandonPending() {
        if (_inFlight || _pendingEvent == null) {
            return false;
        }
        try {
            Storage.deleteValue(PENDING_KEY);
        } catch (error) {
            setState("CONFIGURATION FAILURE", "Cannot abandon pending TEST");
            return true;
        }
        _pendingEvent = null;
        _displayEventId = null;
        setState("READY", "Pending TEST abandoned; press START");
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
        return _view.abandonPending();
    }
}
