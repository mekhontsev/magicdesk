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
        if (displayId <= 0) {
            throw new IllegalArgumentException("invalid display id");
        }
        this.kind = kind;
        this.displayId = displayId;
        this.profileDisplayId = profileDisplayId;
        this.profileKey = profileKey == null ? "" : profileKey;
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
        return profileDisplayId > 0
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
