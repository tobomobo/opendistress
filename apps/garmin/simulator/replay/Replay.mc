// SPDX-License-Identifier: MIT
// Isolated simulator ID. Never package or install on a physical watch.
// Real trigger/payload/persistence/response, with transport and GPS stubbed.
class SendReplayApp extends OpenDistressApp {
    var _fixture;
    var _installTimer;
    function initialize() { OpenDistressApp.initialize(); }
    function onStart(state) {
        var config = {"protocol" => DirectAlertSettings.PROTOCOL,
            "type" => "config", "revision" => "1"};
        for (var i = 0; i < DirectAlertSettings.VALUE_KEYS.size(); i += 1) {
            config[DirectAlertSettings.VALUE_KEYS[i]] = "";
        }
        config["grafanaWebhookUrl"] = "https://synthetic.grafana.net/integrations/v1/formatted_webhook/0123456789abcdef0123456789abcdef/";
        for (var j = 3; j < 10; j += 1) {
            var value = "";
            for (var k = 0; k < DirectAlertSettings.VALUE_LIMITS[j]; k += 1) { value += "x"; }
            config[DirectAlertSettings.VALUE_KEYS[j]] = value;
        }
        config["config_digest"] = DirectAlertSettings.configDigest(config);
        _fixture = config;
        _installTimer = new Timer.Timer();
        _installTimer.start(method(:installFixture), 1000, false);
    }
    function installFixture() as Void {
        if (!DirectAlertSettings.install(_fixture)) { throw new Lang.InvalidValueException("Fixture config"); }
        _view.settingsChanged();
        System.println("REPLAY: maximum-length config installed");
    }
    function getInitialView() {
        _view = new SendReplayView();
        return [_view, new SendReplayDelegate(_view)];
    }
}

class SendReplayView extends OpenDistressView {
    var _responseTimer;
    var _replayContext;
    var _inputTimer;
    var _inputDelegate;
    var _inputStage = 0;
    function initialize() {
        _responseTimer = new Timer.Timer();
        _inputTimer = new Timer.Timer();
        OpenDistressView.initialize();
    }
    function submitGrafanaAlert(endpoint, payload, options) {
        // Payload has already been built by the actual production method.
        if (!(payload instanceof Lang.Dictionary)) { throw new Lang.InvalidValueException("Fixture payload"); }
        _replayContext = options[:context];
        System.println("REPLAY: payload built; simulated response in 4 seconds");
        _responseTimer.start(method(:acceptReplay), 4000, false);
    }
    function acceptReplay() {
        onGrafanaAlertResponse(200, {}, _replayContext);
        System.println(_directResult != null && _queue.size() == 0
            ? "REPLAY PASS: persisted acceptance and empty queue" : "REPLAY FAIL: acceptance missing");
    }
    function requestCompanionLocation() {}
    function captureDirectLocations() {}
    function resumeDirectLocations() {}
    function pollDirectFallbackLocation() {}
    function replayInput(delegate) {
        if (_resetConfirmation) {
            _inputDelegate = delegate; _inputStage = 4;
            delegate.onKeyPressed(new ReplayStartKey());
            _inputTimer.start(method(:advanceInput), 2800, false);
            return true;
        }
        if (_directResult != null || _queue.size() > 0 || _inputStage != 0) { return true; }
        _inputDelegate = delegate;
        _inputStage = 1;
        delegate.onKeyPressed(new ReplayStartKey());
        _inputTimer.start(method(:advanceInput), 300, false);
        return true;
    }
    function advanceInput() {
        if (_inputStage == 4) {
            _inputDelegate.onKeyReleased(new ReplayStartKey());
            _inputDelegate.onKey(new ReplayStartKey());
            System.println(_directResult == null && _queue.size() == 0
                ? "REPLAY PASS: reset returned to ready" : "REPLAY FAIL: reset retained result");
            _inputStage = 0;
        } else if (_inputStage == 1) {
            _inputDelegate.onKeyReleased(new ReplayStartKey());
            _inputDelegate.onKey(new ReplayStartKey());
            System.println(_queue.size() == 0 && _directResult == null
                ? "REPLAY PASS: short press cancelled" : "REPLAY FAIL: short press sent");
            _inputStage = 2;
            _inputTimer.start(method(:advanceInput), 1000, false);
        } else if (_inputStage == 2) {
            _inputDelegate.onKeyPressed(new ReplayStartKey());
            _inputStage = 3;
            _inputTimer.start(method(:advanceInput), 2800, false);
        } else {
            _inputDelegate.onKeyReleased(new ReplayStartKey());
            _inputDelegate.onKey(new ReplayStartKey());
            System.println(_inFlight && _queue.size() == 1
                ? "REPLAY PASS: sustained press queued request" : "REPLAY FAIL: hold did not send");
            _inputStage = 0;
        }
    }
}

class ReplayStartKey { function getKey() { return WatchUi.KEY_START; } }
class SendReplayDelegate extends OpenDistressDelegate {
    function initialize(view) { OpenDistressDelegate.initialize(view); }
    function onPreviousPage() {
        if (_view._resetConfirmation) { return _view.replayInput(self); }
        if (_view.shouldShowAcceptedStatus()) { return _view.menuAction(); }
        if (_view._directResult != null) { return _view.revealAcceptedStatus(); }
        return _view.replayInput(self);
    }
}
