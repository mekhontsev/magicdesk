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
                    && displayTarget.output.kind == DesktopDisplayOutput.Kind.PHONE;
        }
        if (displayId <= Display.DEFAULT_DISPLAY
                || displayTarget == null
                || displayTarget.workspaceDisplayId != displayId) {
            return false;
        }
        return this == SIMULATED
                ? displayTarget.output.kind == DesktopDisplayOutput.Kind.SIMULATED
                : displayTarget.output.kind == DesktopDisplayOutput.Kind.WIRED
                        || displayTarget.output.kind
                                == DesktopDisplayOutput.Kind.WIRELESS;
    }
}
