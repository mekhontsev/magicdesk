package io.github.mekhontsev.magicdesk;

/** Identifies a secondary display that is ready to host the desktop. */
final class DesktopDisplayTarget {
    enum Kind {
        WIRED,
        WIRELESS,
        SIMULATED
    }

    final Kind kind;
    final int displayId;

    private DesktopDisplayTarget(final Kind kind, final int displayId) {
        if (kind == null) {
            throw new IllegalArgumentException("display kind is required");
        }
        if (displayId <= 0) {
            throw new IllegalArgumentException("invalid display id");
        }
        this.kind = kind;
        this.displayId = displayId;
    }

    static DesktopDisplayTarget wired(final int displayId) {
        return new DesktopDisplayTarget(Kind.WIRED, displayId);
    }

    static DesktopDisplayTarget wireless(final int displayId) {
        return new DesktopDisplayTarget(Kind.WIRELESS, displayId);
    }

    static DesktopDisplayTarget simulated(final int displayId) {
        return new DesktopDisplayTarget(Kind.SIMULATED, displayId);
    }
}
