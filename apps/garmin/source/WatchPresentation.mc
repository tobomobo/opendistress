// SPDX-License-Identifier: MIT
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.Math;
import Toybox.System;
import Toybox.Time;
import Toybox.Time.Gregorian;
import Toybox.WatchUi;

// Presentation only: no provider, storage, trigger, or tracking authority.
module WatchPresentation {
    // Brand palette (docs/branding.md) on a black AMOLED background. The 1-bit
    // compact display ignores these and keeps white-only strokes.
    const AMBER = 0xF5A623;
    const AMBER_SOFT = 0x8F5F12;
    const AMBER_DIM = 0x3D2A08;
    const WARM_WHITE = 0xF4F0E6;
    const TRACK = 0x2E2E2E;
    const RESET_GLOW = 0x3C3A36;
    const ERROR_RED = 0xC8323C;
    // Hold beats as a percentage of the hold. They accelerate towards the
    // threshold and coincide with WatchFeedback.tick(); the press is beat zero.
    const HOLD_BEAT_PERCENT = [40, 68, 88];
    const BEAT_FLARE_MS = 260;
    const SIGNAL_ORBIT_MS = 1400;

    // One native line per slot: TextArea can clip glyphs at a percentage-height
    // boundary even when it has selected a nominally fitting font.
    function line(dc, value, centerY, prominent) {
        lineColor(dc, value, centerY, prominent,
            prominent ? Graphics.COLOR_WHITE : Graphics.COLOR_LT_GRAY);
    }

    function lineColor(dc, value, centerY, prominent, color) {
        lineFit(dc, value, centerY, prominent, color, 0.72);
    }

    // `fraction` narrows the slot where a line sits close to the ring.
    function lineFit(dc, value, centerY, prominent, color, fraction) {
        var fonts = prominent ? [Graphics.FONT_LARGE, Graphics.FONT_MEDIUM,
            Graphics.FONT_SMALL, Graphics.FONT_TINY, Graphics.FONT_XTINY]
            : [Graphics.FONT_TINY, Graphics.FONT_XTINY];
        var font = fonts[fonts.size() - 1];
        for (var i = 0; i < fonts.size(); i += 1) {
            if (dc.getTextWidthInPixels(value, fonts[i]) <= dc.getWidth() * fraction) {
                font = fonts[i]; break;
            }
        }
        dc.setColor(isCompact(dc) ? Graphics.COLOR_WHITE : color, Graphics.COLOR_BLACK);
        dc.drawText(dc.getWidth() / 2, dc.getHeight() * centerY / 100,
            font, value, Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
    }
    function isCompact(dc) { return dc.getWidth() == dc.getHeight() && dc.getWidth() < 220; }

    // Instinct Solar has a 23px minimum native font on a 176px display. Percent
    // height TextAreas can silently omit it. These single-line slots use native
    // text drawing and keep the upper-right hardware sub-window clear.
    function compactLine(dc, value, top, header) {
        dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
        dc.drawText(dc.getWidth() * (header ? 0.32 : 0.50), dc.getHeight() * top / 100,
            Graphics.FONT_XTINY, value, Graphics.TEXT_JUSTIFY_CENTER);
    }

    function text(dc, value, top, height) {
        var w = dc.getWidth(); var h = dc.getHeight();
        var compact = w == h && w < 220;
        var area = new WatchUi.TextArea({:text => value,
            :color => Graphics.COLOR_LT_GRAY, :backgroundColor => Graphics.COLOR_BLACK,
            :font => [Graphics.FONT_TINY, Graphics.FONT_XTINY],
            :justification => Graphics.TEXT_JUSTIFY_CENTER,
            :locX => w * (compact ? 0.07 : 0.13), :locY => h * top / 100,
            :width => w * (compact ? 0.70 : 0.74), :height => h * height / 100});
        area.draw(dc);
    }
    function progress(dc, elapsed, duration) {
        holdRing(dc, elapsed, duration, false);
    }

    // [centre x, centre y, radius, core pen]. The ring sits just inside the
    // physical-key indicators; compact 1-bit keeps its verified outer edge.
    function ringGeometry(dc) {
        var w = dc.getWidth(); var h = dc.getHeight();
        var size = w < h ? w : h;
        if (isCompact(dc)) { return [w / 2, h / 2, size * 0.48, 3]; }
        var pen = size / 34;
        if (pen < 5) { pen = 5; }
        return [w / 2, h / 2, size / 2 - size / 24, pen];
    }

    function smooth(dc, enabled) {
        if (dc has :setAntiAlias) { dc.setAntiAlias(enabled); }
    }

    // Beats reached so far; a new beat is the cue for one haptic tick.
    function holdBeat(elapsed, duration) {
        var beat = 0;
        for (var i = 0; i < HOLD_BEAT_PERCENT.size(); i += 1) {
            if (elapsed >= 0 && elapsed * 100 >= duration * HOLD_BEAT_PERCENT[i]) { beat = i + 1; }
        }
        return beat;
    }

    // 1.0 at the press and at every beat, decaying to 0 within BEAT_FLARE_MS.
    function holdFlare(elapsed, duration) {
        if (elapsed < 0 || duration <= 0) { return 0.0; }
        var last = 0;
        for (var i = 0; i < HOLD_BEAT_PERCENT.size(); i += 1) {
            var at = duration * HOLD_BEAT_PERCENT[i] / 100;
            if (elapsed >= at) { last = at; }
        }
        var since = elapsed - last;
        return since >= BEAT_FLARE_MS ? 0.0 : 1.0 - since.toFloat() / BEAT_FLARE_MS;
    }

    function arcPair(dc, x, y, r, sweep) {
        dc.drawArc(x, y, r, Graphics.ARC_CLOCKWISE, 270, (630 - sweep) % 360);
        dc.drawArc(x, y, r, Graphics.ARC_COUNTER_CLOCKWISE, 270, (270 + sweep) % 360);
    }

    function ringPoint(x, y, r, degrees) {
        var radians = Math.toRadians(degrees);
        return [x + r * Math.cos(radians), y - r * Math.sin(radians)];
    }

    // Deliberate-hold ring. It closes from six o'clock in both directions over
    // real elapsed time only; the beat flare is decoration and never timing.
    // The reset variant is warm white so it cannot be mistaken for sending.
    function holdRing(dc, elapsed, duration, reset) {
        var g = ringGeometry(dc);
        var x = g[0]; var y = g[1]; var r = g[2]; var pen = g[3];
        if (elapsed < 0) { elapsed = 0; }
        if (elapsed > duration) { elapsed = duration; }
        var sweep = elapsed * 180 / duration;
        var mono = isCompact(dc);
        var flare = holdFlare(elapsed, duration);
        var core = mono ? Graphics.COLOR_WHITE : (reset ? WARM_WHITE : AMBER);
        smooth(dc, true);
        // The whole path lights up while held, so the goal is visible at once.
        dc.setColor(mono ? Graphics.COLOR_DK_GRAY : (reset ? RESET_GLOW : AMBER_DIM),
            Graphics.COLOR_BLACK);
        dc.setPenWidth(mono ? 3 : pen / 3 + 1);
        dc.drawCircle(x, y, r);
        if (sweep > 0) {
            if (!mono) {
                dc.setColor(reset ? RESET_GLOW : (flare > 0.45 ? AMBER_SOFT : AMBER_DIM),
                    Graphics.COLOR_BLACK);
                dc.setPenWidth(pen + (pen * (0.8 + flare)).toNumber());
                arcPair(dc, x, y, r, sweep);
            }
            dc.setColor(core, Graphics.COLOR_BLACK);
            dc.setPenWidth(mono ? 3 + (2 * flare).toNumber() : pen);
            arcPair(dc, x, y, r, sweep);
            var tipRadius = mono ? 3 + 2 * flare : pen * (0.55 + 0.45 * flare);
            var tips = [ringPoint(x, y, r, 270 - sweep), ringPoint(x, y, r, 270 + sweep)];
            for (var i = 0; i < tips.size(); i += 1) {
                if (!mono && flare > 0) {
                    // A ping leaves each tip on every beat, in time with the tick.
                    dc.setColor(reset ? WARM_WHITE : AMBER_SOFT, Graphics.COLOR_BLACK);
                    dc.setPenWidth(2);
                    dc.drawCircle(tips[i][0], tips[i][1], pen * (0.9 + 1.4 * (1.0 - flare)));
                }
                dc.setColor(mono ? Graphics.COLOR_WHITE : WARM_WHITE, Graphics.COLOR_BLACK);
                dc.fillCircle(tips[i][0], tips[i][1], tipRadius);
            }
        }
        smooth(dc, false);
        dc.setPenWidth(1);
    }

    // Idle mark: a quiet open ring with the amber signal point at six o'clock.
    function readyRing(dc) {
        readyRingWithGps(dc, true);
    }

    // The signal point is hollow while the watch is still searching for GPS.
    function readyRingWithGps(dc, gpsReady) {
        if (isCompact(dc)) { return; }
        var g = ringGeometry(dc);
        smooth(dc, true);
        dc.setColor(TRACK, Graphics.COLOR_BLACK);
        dc.setPenWidth(2);
        dc.drawCircle(g[0], g[1], g[2]);
        dc.setColor(AMBER, Graphics.COLOR_BLACK);
        if (gpsReady) {
            dc.fillCircle(g[0], g[1] + g[2], g[3] * 0.6);
        } else {
            dc.setColor(Graphics.COLOR_BLACK, Graphics.COLOR_BLACK);
            dc.fillCircle(g[0], g[1] + g[2], g[3] * 0.6);
            dc.setColor(AMBER, Graphics.COLOR_BLACK);
            dc.setPenWidth(2);
            dc.drawCircle(g[0], g[1] + g[2], g[3] * 0.5);
        }
        smooth(dc, false);
        dc.setPenWidth(1);
    }

    // Stored-signal ring after the hold. `burst` (1 → 0) flashes the closed
    // ring once; `active` orbits a light while a request or Wi-Fi check runs.
    // It is local request activity, never provider or recipient evidence.
    function signalRing(dc, burst, active, blocked) {
        if (isCompact(dc)) { return; }
        var g = ringGeometry(dc);
        var x = g[0]; var y = g[1]; var r = g[2]; var pen = g[3];
        smooth(dc, true);
        if (blocked) {
            dc.setColor(ERROR_RED, Graphics.COLOR_BLACK);
            dc.setPenWidth(pen / 2);
            dc.drawCircle(x, y, r);
            smooth(dc, false);
            dc.setPenWidth(1);
            return;
        }
        if (burst > 0) {
            dc.setColor(AMBER_SOFT, Graphics.COLOR_BLACK);
            dc.setPenWidth(pen + (pen * 1.8 * burst).toNumber());
            dc.drawCircle(x, y, r);
        }
        dc.setColor(burst > 0.35 ? WARM_WHITE : AMBER_DIM, Graphics.COLOR_BLACK);
        dc.setPenWidth(burst > 0.35 ? pen : pen / 2 + 1);
        dc.drawCircle(x, y, r);
        if (active && burst <= 0.35) {
            var head = 90 - (System.getTimer() % SIGNAL_ORBIT_MS) * 360 / SIGNAL_ORBIT_MS;
            var tail = head + 54;
            if (head < 0) { head += 360; }
            if (tail >= 360) { tail -= 360; }
            if (tail < 0) { tail += 360; }
            dc.setColor(AMBER, Graphics.COLOR_BLACK);
            dc.setPenWidth(pen);
            dc.drawArc(x, y, r, Graphics.ARC_CLOCKWISE, tail, head);
            var point = ringPoint(x, y, r, head);
            dc.setColor(WARM_WHITE, Graphics.COLOR_BLACK);
            dc.fillCircle(point[0], point[1], pen * 0.6);
        }
        smooth(dc, false);
        dc.setPenWidth(1);
    }
    // Angles are projected from the SDK simulator's physical key centres.
    // Resource overrides distinguish Forerunner/Instinct and two-button Venu.
    function buttonAngle(action) {
        var values = WatchUi.loadResource(Rez.Strings.ButtonGeometry) as Lang.String;
        var index = action.equals("START") ? 0 : (action.equals("MENU") ? 1
            : (action.equals("DOWN") ? 2 : 3));
        return values.substring(index * 4, index * 4 + 3).toNumber();
    }

    function hasMenuButton() {
        return buttonAngle("MENU") >= 0;
    }

    function button(dc, action, label, strength) {
        var angle = buttonAngle(action);
        if (angle < 0) { return; }
        var width = dc.getWidth();
        var height = dc.getHeight();
        var size = width < height ? width : height;
        var pen = size >= 400 ? 5 : (size >= 260 ? 4 : 2);
        var bright = strength > 0;
        dc.setColor(bright ? Graphics.COLOR_WHITE : Graphics.COLOR_LT_GRAY,
            Graphics.COLOR_BLACK);
        dc.setPenWidth(pen + (strength * pen * 1.6).toNumber());
        if (width == height) {
            var half = 6 + strength * 10;
            dc.drawArc(width / 2, height / 2, size / 2 - pen * 2,
                Graphics.ARC_CLOCKWISE, angle + half, angle - half);
        } else {
            // Venu X1 simulator key centres: 25% and 75% down the right edge.
            var y = height * (action.equals("START") ? 0.25 : 0.75);
            var half = height * (0.018 + strength * 0.025);
            dc.drawLine(width - pen * 2, y - half, width - pen * 2, y + half);
        }
        dc.setPenWidth(1);
        if (label.length() == 0) { return; }
        var right = angle < 90 || angle > 270;
        var yPercent = action.equals("MENU") ? 69 : (action.equals("START") ? 27 : 69);
        var labelWidth = width * 0.40;
        var x = right ? width * 0.51 : width * 0.09;
        if (action.equals("MENU")) {
            // A leader ties the reset label to middle-left, not lower-left.
            dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_BLACK);
            dc.drawLine(width * 0.055, height * 0.50, width * 0.065, height * 0.72);
            dc.drawLine(width * 0.065, height * 0.72, width * 0.09, height * 0.72);
        }
        var area = new WatchUi.TextArea({
            :text => label, :color => bright ? Graphics.COLOR_WHITE : Graphics.COLOR_LT_GRAY,
            :backgroundColor => Graphics.COLOR_BLACK,
            :font => [Graphics.FONT_TINY, Graphics.FONT_XTINY],
            :justification => right ? Graphics.TEXT_JUSTIFY_RIGHT : Graphics.TEXT_JUSTIFY_LEFT,
            :locX => x, :locY => height * yPercent / 100,
            :width => labelWidth, :height => height * 0.12
        });
        area.draw(dc);
    }

    function drawClock(dc) {
        var w = dc.getWidth();
        var h = dc.getHeight();
        var size = w < h ? w : h;
        var compact = size < 220;
        // Keep the Instinct Solar's hardware sub-window clear.
        var cx = compact ? w * 0.43 : w * 0.5;
        var cy = compact ? h * 0.61 : h * 0.5;
        var panelW = size * (compact ? 0.71 : 0.78);
        var panelH = size * (compact ? 0.49 : 0.53);
        var left = cx - panelW / 2;
        var top = cy - panelH / 2;
        var ink = Graphics.COLOR_LT_GRAY;
        dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_BLACK);
        dc.setPenWidth(size >= 300 ? 3 : 1);
        dc.drawRoundedRectangle(left, top, panelW, panelH, size * 0.045);
        dc.drawLine(left + panelW * 0.07, top + panelH * 0.29,
            left + panelW * 0.93, top + panelH * 0.29);
        dc.setPenWidth(1);
        var clock = System.getClockTime();
        var hour = clock.hour;
        var is24 = System.getDeviceSettings().is24Hour;
        if (!is24) { hour = hour % 12; if (hour == 0) { hour = 12; } }
        var info = Gregorian.info(Time.now(), Time.FORMAT_SHORT);
        var days = ["SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"];
        var date = days[info.day_of_week - 1] + "  "
            + info.day.format("%02d") + "." + info.month.format("%02d");
        dc.setColor(ink, Graphics.COLOR_BLACK);
        dc.drawText(cx, top + panelH * 0.15, Graphics.FONT_XTINY, date,
            Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        var digitW = panelW * 0.155;
        var digitH = panelH * 0.42;
        var gap = panelW * 0.04;
        var colonW = panelW * 0.065;
        var digitsLeft = cx - (4 * digitW + 2 * gap + colonW) / 2;
        var digitTop = top + panelH * 0.38;
        digit(dc, hour / 10, digitsLeft, digitTop, digitW, digitH, ink);
        digit(dc, hour % 10, digitsLeft + digitW + gap, digitTop, digitW, digitH, ink);
        var colonX = digitsLeft + 2 * digitW + gap + colonW / 2;
        dc.fillCircle(colonX, digitTop + digitH * 0.32, size >= 300 ? 3 : 1);
        dc.fillCircle(colonX, digitTop + digitH * 0.68, size >= 300 ? 3 : 1);
        var minsLeft = digitsLeft + 2 * digitW + gap + colonW;
        digit(dc, clock.min / 10, minsLeft, digitTop, digitW, digitH, ink);
        digit(dc, clock.min % 10, minsLeft + digitW + gap, digitTop, digitW, digitH, ink);
        if (!compact) {
            dc.setColor(Graphics.COLOR_LT_GRAY, Graphics.COLOR_BLACK);
            dc.drawText(cx, top + panelH + size * 0.07, Graphics.FONT_XTINY,
                is24 ? "24H" : (clock.hour >= 12 ? "PM" : "AM"),
                Graphics.TEXT_JUSTIFY_CENTER | Graphics.TEXT_JUSTIFY_VCENTER);
        }
    }

    // Original seven-segment artwork; no third-party face/assets or fake telemetry.
    function digit(dc, value, x, y, w, h, color) {
        var masks = [63, 6, 91, 79, 102, 109, 125, 7, 127, 111];
        var mask = masks[value.toNumber()];
        var t = w * 0.13;
        dc.setColor(color, Graphics.COLOR_BLACK);
        if ((mask & 1) != 0) { dc.fillRectangle(x + t, y, w - 2*t, t); }
        if ((mask & 2) != 0) { dc.fillRectangle(x + w - t, y + t, t, h/2 - 1.5*t); }
        if ((mask & 4) != 0) { dc.fillRectangle(x + w - t, y + h/2 + t/2, t, h/2 - 1.5*t); }
        if ((mask & 8) != 0) { dc.fillRectangle(x + t, y + h - t, w - 2*t, t); }
        if ((mask & 16) != 0) { dc.fillRectangle(x, y + h/2 + t/2, t, h/2 - 1.5*t); }
        if ((mask & 32) != 0) { dc.fillRectangle(x, y + t, t, h/2 - 1.5*t); }
        if ((mask & 64) != 0) { dc.fillRectangle(x + t, y + h/2 - t/2, w - 2*t, t); }
    }
}
