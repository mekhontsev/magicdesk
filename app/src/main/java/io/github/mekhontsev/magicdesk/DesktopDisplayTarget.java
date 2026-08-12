package io.github.mekhontsev.magicdesk;

/** Identifies one display environment that is ready to host the desktop. */
final class DesktopDisplayTarget {
    enum Kind {
        PHONE,
        WIRED,
        WIRELESS,
        SIMULATED
    }

    final Kind kind;
    final int displayId;
    final int profileDisplayId;
    final String profileKey;

    private DesktopDisplayTarget(
            final Kind kind,
            final int displayId,
            final int profileDisplayId,
            final String profileKey) {
        if (kind == null) {
            throw new IllegalArgumentException("display kind is required");
        }
        if (kind == Kind.PHONE
                ? displayId != android.view.Display.DEFAULT_DISPLAY
                : displayId <= android.view.Display.DEFAULT_DISPLAY) {
            throw new IllegalArgumentException("invalid display id");
        }
        this.kind = kind;
        this.displayId = displayId;
        this.profileDisplayId = profileDisplayId;
        this.profileKey = profileKey == null ? "" : profileKey;
    }

    static DesktopDisplayTarget phone() {
        return new DesktopDisplayTarget(
                Kind.PHONE,
                android.view.Display.DEFAULT_DISPLAY,
                android.view.Display.DEFAULT_DISPLAY,
                "");
    }

    static DesktopDisplayTarget wired(final int displayId) {
        return new DesktopDisplayTarget(Kind.WIRED, displayId, displayId, "");
    }

    static DesktopDisplayTarget wireless(final int displayId) {
        return new DesktopDisplayTarget(
                Kind.WIRELESS, displayId, displayId, "");
    }

    static DesktopDisplayTarget simulated(final int displayId) {
        return new DesktopDisplayTarget(
                Kind.SIMULATED, displayId, displayId, "");
    }

    static DesktopDisplayTarget restore(
            final Kind kind,
            final int displayId,
            final int profileDisplayId,
            final String profileKey) {
        final DesktopDisplayTarget target = new DesktopDisplayTarget(
                kind, displayId, displayId, "");
        return kind != Kind.PHONE
                        && profileDisplayId > 0
                        && profileKey != null
                        && !profileKey.isEmpty()
                ? target.withProfile(profileDisplayId, profileKey)
                : target;
    }

    DesktopDisplayTarget withProfile(
            final int newProfileDisplayId,
            final String newProfileKey) {
        if (newProfileDisplayId <= 0
                || newProfileKey == null
                || newProfileKey.isEmpty()) {
            throw new IllegalArgumentException("invalid display profile");
        }
        return new DesktopDisplayTarget(
                kind, displayId, newProfileDisplayId, newProfileKey);
    }

    boolean hasProfile() {
        return profileDisplayId > 0 && !profileKey.isEmpty();
    }
}
