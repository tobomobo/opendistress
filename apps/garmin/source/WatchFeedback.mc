// SPDX-License-Identifier: MIT
import Toybox.Attention;

// The same optional cues are used in practice and in the real TEST flow.
// A cue has no persistence, submission or recipient-evidence authority.
// Duty cycles stay low so the cues are felt on the wrist, not heard nearby.
module WatchFeedback {
    // A deliberate press began.
    function input() { vibrate([new Attention.VibeProfile(15, 60)]); }
    // A deliberate hold passed one of its beats; nothing has been stored yet.
    function tick() { vibrate([new Attention.VibeProfile(12, 35)]); }
    // Local persistence completed (signal stored or TEST reset). Longer than a
    // tick and single, so it cannot be mistaken for the provider double pulse.
    function committed() { vibrate([new Attention.VibeProfile(25, 120)]); }
    // A provider accepted the request (or practice simulates that acceptance).
    function accepted() {
        vibrate([new Attention.VibeProfile(15, 100),
            new Attention.VibeProfile(0, 80), new Attention.VibeProfile(15, 100)]);
    }
    function vibrate(pattern) {
        try {
            if (!DirectAlertSettings.hapticsEnabled() || !(Attention has :vibrate)) { return; }
            Attention.vibrate(pattern);
        } catch (error) { /* Optional feedback never gates the action. */ }
    }
}
