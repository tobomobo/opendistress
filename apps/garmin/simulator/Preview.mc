// SPDX-License-Identifier: MIT
// Explicit offline UI fixture. Excluded from monkey.jungle and beta.jungle.
// No provider credentials, network calls, or physical location evidence.
class PreviewApp extends OpenDistressApp {
    function initialize() { AppBase.initialize(); }
    function getInitialView() {
        var view = new PreviewView();
        return [view, new PreviewDelegate(view)];
    }
}

class PreviewView extends OpenDistressView {
    var _scenario = 0;
    function initialize() {
        OpenDistressView.initialize();
        _queue = []; _directResult = null; _activeIncident = null;
        _mode = "DIRECT_TEST"; _state = "READY — TEST";
        if (!WatchPresentation.hasMenuButton()) {
            showAcceptedFixture(); _acceptedStatusVisible = true;
            _statusInteractionAtMs = System.getTimer();
        }
    }
    function onShow() { _visible = true; }
    function selectStartupMode() { _mode = "DIRECT_TEST"; _state = "READY — TEST"; }
    function sendPending() {}
    function activateLive() {}
    function captureLocations() {}
    function resumeDirectLocations() {}
    function resumeLocations() {}
    function pollDirectFallbackLocation() {}
    function activateTest() { showAcceptedFixture(); }
    function showAcceptedFixture() {
        _armingAlert = false; _retryTimer.stop();
        _queue = []; _state = "PROVIDER ACCEPTED";
        _acceptedStatusVisible = false;
        _directResult = {
            "grafana_accepted" => true, "pushover_accepted" => false,
            "last_location_hex" => "", "pending_location_hex" => "",
            "accepted_at" => 0, "capture_stage" => 3
        };
        WatchUi.requestUpdate();
    }
    function resetAcceptedTest() {
        _resetConfirmation = false; _resetHolding = false;
        _directResult = null; _acceptedStatusVisible = false;
        _acceptedActionFeedback = null; _queue = [];
        _state = "READY — TEST";
        WatchUi.requestUpdate();
    }
    // UP cycles fixtures without requiring a real alert or an automation long press.
    function nextFixture() {
        _scenario = (_scenario + 1) % 10;
        _retryTimer.stop(); _statusTimer.stop();
        _pressedButton = null; _acceptedActionFeedback = null;
        _directResult = null; _acceptedStatusVisible = false; _armingAlert = false;
        _resetConfirmation = false; _resetHolding = false;
        _queue = []; _inFlight = false; _retryScheduled = false; _resetDone = false;
        _signalStored = false;
        _state = "READY — TEST";
        if (_scenario == 1) {
            _armingAlert = true; _armStartedAtMs = System.getTimer() - 1250;
        } else if (_scenario == 2) {
            // Synthetic stored TEST with a simulated request in flight; nothing is sent.
            _queue = [{"v" => 1, "event_id" => "PREVIEW"}]; _inFlight = true;
            _signalStored = true; _signalStoredAtMs = System.getTimer();
            _state = "SENDING TEST"; _detail = "Phone reported connected; route not forced";
        } else if (_scenario == 7) {
            openPractice();
        } else if (_scenario == 8) {
            _queue = [{"v" => 1, "event_id" => "PREVIEW"}];
            _retryScheduled = true; _nextRetryAtMs = System.getTimer() + 30000;
            _state = "TEST PENDING"; _detail = "No phone link; TEST not sent yet";
        } else if (_scenario == 9) {
            _resetDone = true; _resetDoneAtMs = System.getTimer() + 600000;
        } else if (_scenario >= 3) {
            showAcceptedFixture();
            _acceptedStatusVisible = _scenario >= 4;
            _resetConfirmation = _scenario == 5;
            if (_scenario == 6) { _directResult["pushover_accepted"] = true; }
            _statusInteractionAtMs = System.getTimer();
        }
        WatchUi.requestUpdate();
        scheduleAmbient();
        return true;
    }
}

class PreviewDelegate extends OpenDistressDelegate {
    function initialize(view) { OpenDistressDelegate.initialize(view); }
    function onPreviousPage() { return _view.nextFixture(); }
    function onSwipe(event) { return _view.nextFixture(); }
}
