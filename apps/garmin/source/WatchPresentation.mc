// SPDX-License-Identifier: MIT
import Toybox.Graphics;
import Toybox.Lang;
import Toybox.System;
import Toybox.Time;
import Toybox.Time.Gregorian;
import Toybox.WatchUi;

// Presentation only: no provider, storage, trigger, or tracking authority.
module WatchPresentation {
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
        var w = dc.getWidth(); var h = dc.getHeight();
        var size = w < h ? w : h;
        var sweep = elapsed < 0 ? 0 : (elapsed >= duration ? 180 : elapsed * 180 / duration);
        var compact = w == h && w < 220;
        var x = w * 0.5; var y = h * 0.5;
        var r = size * (compact ? 0.48 : 0.45);
        dc.setColor(Graphics.COLOR_DK_GRAY, Graphics.COLOR_BLACK); dc.setPenWidth(3);
        dc.drawCircle(x, y, r);
        if (sweep > 0) {
            dc.setColor(Graphics.COLOR_WHITE, Graphics.COLOR_BLACK);
            dc.drawArc(x, y, r, Graphics.ARC_CLOCKWISE, 270, (630 - sweep) % 360);
            dc.drawArc(x, y, r, Graphics.ARC_COUNTER_CLOCKWISE, 270, (270 + sweep) % 360);
        }
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
