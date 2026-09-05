// SPDX-License-Identifier: MIT
import Toybox.Attention;

// The same optional cues are used in practice and in the real TEST flow.
// A cue has no persistence, submission or recipient-evidence authority.
module WatchFeedback {
    function input() { vibrate(false); }
    function accepted() { vibrate(true); }
    function vibrate(doublePulse) {
        try {
            if (!DirectAlertSettings.hapticsEnabled() || !(Attention has :vibrate)) { return; }
            Attention.vibrate(doublePulse ? [new Attention.VibeProfile(15, 100),
                new Attention.VibeProfile(0, 80), new Attention.VibeProfile(15, 100)]
                : [new Attention.VibeProfile(15, 60)]);
        } catch (error) { /* Optional feedback never gates the action. */ }
    }
}
