package io.github.mekhontsev.magicdesk;

import android.view.Display;

/** Selects the display environment used by the desktop self-test. */
enum DesktopSelfTestTarget {
    SIMULATED,
    EXTERNAL,
    PHONE;

    boolean matchesDisplay(
            final int displayId,
            final DesktopDisplayTarget displayTarget) {
        if (this == PHONE) {
            return displayId == Display.DEFAULT_DISPLAY
                    && displayTarget != null
                    && displayTarget.kind == DesktopDisplayTarget.Kind.PHONE;
        }
        if (displayId <= Display.DEFAULT_DISPLAY
                || displayTarget == null
                || displayTarget.displayId != displayId) {
            return false;
        }
        return this == SIMULATED
                ? displayTarget.kind == DesktopDisplayTarget.Kind.SIMULATED
                : displayTarget.kind == DesktopDisplayTarget.Kind.WIRED
                        || displayTarget.kind
                                == DesktopDisplayTarget.Kind.WIRELESS;
    }
}
