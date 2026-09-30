// SPDX-License-Identifier: MIT
import Toybox.Graphics;
import Toybox.System;
import Toybox.Timer;
import Toybox.WatchUi;

// Deliberately NOT an OpenDistressView subclass. No provider, queue, GPS,
// application-settings mutation, or alert delegate is reachable from this view.
// Practice never sets readiness or creates provider-acceptance evidence.
class WatchPracticeView extends WatchUi.View {
    const HOLD_MS = 2500;
    // Practice plays the real cue order: ticks, stored signal, then a clearly
    // simulated provider double pulse after a typical sending delay.
    const SIMULATED_ACCEPT_MS = 1500;
    var _step = 0; // intro, short press, visible hold, eyes-away hold, done
    var _pressedStep = -1;
    var _pressedAt = 0;
    var _held = false;
    var _visible = false;
    var _beat = 0;
    var _acceptCueAt = 0;
    var _acceptCuePending = false;
    var _timer;
    function initialize() { View.initialize(); _timer = new Timer.Timer(); }
    function onShow() { _visible = true; }
    function onHide() {
        _visible = false; _held = false; _pressedStep = -1; _acceptCuePending = false; _timer.stop();
    }
    function press() {
        if (!_visible || _pressedStep >= 0) { return true; }
        _pressedStep = _step; _pressedAt = System.getTimer(); _held = true; _beat = 0;
        if (_step > 0 && _step < 4) {
            WatchFeedback.input();
            // Stop first: a simulated acceptance cue may still own the timer.
            try { _timer.stop(); _timer.start(method(:advance), 50, true); }
            catch (error) { _held = false; _pressedStep = -1; }
        }
        WatchUi.requestUpdate(); return true;
    }
    function release() {
        var elapsed = System.getTimer() - _pressedAt;
        if (_visible && _held && _pressedStep == _step) {
            if (_step == 0 || (_step == 1 && elapsed >= 0 && elapsed < HOLD_MS)) { _step += 1; }
        }
        _held = false; _pressedStep = -1;
        if (!_acceptCuePending) { _timer.stop(); }
        WatchUi.requestUpdate(); return true;
    }
    function advance() {
        if (_acceptCuePending && System.getTimer() - _acceptCueAt >= 0) {
            _acceptCuePending = false;
            // This is explicitly a simulated provider cue, not a sent event.
            WatchFeedback.accepted();
            if (!_held) { _timer.stop(); }
        }
        if (!_visible || !_held || _pressedStep != _step) { return; }
        var elapsed = System.getTimer() - _pressedAt;
        if (elapsed < 0) { release(); return; }
        if (_step >= 2 && _step <= 3 && elapsed >= HOLD_MS) {
            _step += 1; _held = false;
            WatchFeedback.committed();
            _acceptCuePending = true;
            _acceptCueAt = System.getTimer() + SIMULATED_ACCEPT_MS;
        } else if (_step >= 2 && _step <= 3) {
            var beat = WatchPresentation.holdBeat(elapsed, HOLD_MS);
            if (beat > _beat) { _beat = beat; WatchFeedback.tick(); }
        }
        WatchUi.requestUpdate();
    }
    function onUpdate(dc) {
        dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK); dc.clear();
        if (WatchPresentation.isCompact(dc)) {
            WatchPresentation.compactLine(dc, "PRACTICE", 16, true);
            var lines = [
                ["No sending", "START to begin", "BACK exits"],
                ["Short press", "Press START", "Release early"],
                ["Hold 2.5 sec", "Hold START", "Release cancels"],
                ["Look away", "Hold START 2.5s", "long buzz = done"],
                ["Practice done", "Nothing was sent", "BACK to app"]
            ];
            for (var i = 0; i < 3; i += 1) {
                WatchPresentation.compactLine(dc, lines[_step][i], 40 + i * 14, false);
            }
        } else {
        WatchPresentation.text(dc, "PRACTICE ONLY", 13, 13);
        var titles = ["No sending", "Short press", "Hold 2.5 sec", "Look away", "Practice done"];
        var descriptions = ["START to begin\nBACK exits",
            "Press START\nRelease early",
            _held ? "Keep holding\nRelease cancels" : "Hold START\nRelease cancels",
            "Repeat without looking\nTicks, then a long buzz",
            "Nothing was sent\nBACK returns to app"];
        WatchPresentation.text(dc, titles[_step], 38, 18);
        WatchPresentation.text(dc, descriptions[_step], 58, 26);
        }
        // The blind rehearsal intentionally supplies no visual progress.
        if (_held && _step == 2) {
            WatchPresentation.holdRing(dc, System.getTimer() - _pressedAt, HOLD_MS, false);
        }
        WatchPresentation.button(dc, "START", "", _held ? 1.0 : 0);
        WatchPresentation.button(dc, "BACK", "", 0);
    }
}

class WatchPracticeDelegate extends WatchUi.BehaviorDelegate {
    var _view;
    function initialize(view) { BehaviorDelegate.initialize(); _view = view; }
    function onSelect() { return true; }
    function onTap(event) { return true; }
    function onHold(event) { return true; }
    function onMenu() { return true; }
    function onKeyPressed(event) {
        var key = event.getKey();
        return key == WatchUi.KEY_START || key == WatchUi.KEY_ENTER ? _view.press() : false;
    }
    function onKeyReleased(event) {
        var key = event.getKey();
        return key == WatchUi.KEY_START || key == WatchUi.KEY_ENTER ? _view.release() : false;
    }
    function onBack() { WatchUi.popView(WatchUi.SLIDE_IMMEDIATE); return true; }
}

(:test)
function practiceRequiresSeparatePressesAndNeverCompletesWhenHidden(logger) {
    var practice = new WatchPracticeView();
    practice.onShow(); practice.press(); practice.release();
    if (practice._step != 1) { return false; }
    practice.press(); practice._pressedAt = System.getTimer() - 2600; practice.release();
    if (practice._step != 1) { return false; } // Short-press task must actually be short.
    practice.press(); practice.release();
    if (practice._step != 2) { return false; }
    practice.press(); practice._pressedAt = System.getTimer() - 2500; practice.advance();
    practice.advance(); practice.press();
    if (practice._step != 3 || practice._held) { return false; } // Release before second hold.
    practice.release(); practice.press(); practice.onHide(); practice.advance();
    if (practice._step != 3) { return false; }
    practice.onShow(); practice.press(); practice._pressedAt = System.getTimer() - 2500;
    practice.advance(); practice.release(); practice.onHide();
    return practice._step == 4;
}
