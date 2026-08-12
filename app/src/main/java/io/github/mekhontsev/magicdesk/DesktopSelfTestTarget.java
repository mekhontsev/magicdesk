package io.github.mekhontsev.magicdesk;

import android.view.Display;

/** Selects the display environment used by the desktop self-test. */
enum DesktopSelfTestTarget {
    SIMULATED,
    EXTERNAL,
    PHONE;

    boolean matchesDisplay(
            final int displayId,
            final DesktopDisplayTarget.Kind kind) {
        if (this == PHONE) {
            return displayId == Display.DEFAULT_DISPLAY;
        }
        if (displayId <= Display.DEFAULT_DISPLAY) {
            return false;
        }
        return this == SIMULATED
                ? kind == DesktopDisplayTarget.Kind.SIMULATED
                : kind == DesktopDisplayTarget.Kind.WIRED
                        || kind == DesktopDisplayTarget.Kind.WIRELESS;
    }
}
