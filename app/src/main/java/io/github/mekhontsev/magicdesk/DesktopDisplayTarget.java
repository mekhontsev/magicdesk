package io.github.mekhontsev.magicdesk;

/** Identifies one display environment that is ready to host the desktop. */
final class DesktopDisplayTarget {
    enum Kind {
        PHONE,
        WIRED,
        WIRELESS,
        SIMULATED
    }

    enum ActivationSource {
        MAGICDESK_REQUESTED("magicdesk-requested"),
        ADOPTED_EXISTING("adopted-existing"),
        UNKNOWN("unknown");

        final String diagnosticLabel;

        ActivationSource(final String diagnosticLabel) {
            this.diagnosticLabel = diagnosticLabel;
        }
    }

    final Kind kind;
    final int displayId;
    final int profileDisplayId;
    final String profileKey;
    final ActivationSource activationSource;

    private DesktopDisplayTarget(
            final Kind kind,
            final int displayId,
            final int profileDisplayId,
            final String profileKey,
            final ActivationSource activationSource) {
        if (kind == null || activationSource == null) {
            throw new IllegalArgumentException(
                    "display kind and activation source are required");
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
        this.activationSource = activationSource;
    }

    static DesktopDisplayTarget phone() {
        return new DesktopDisplayTarget(
                Kind.PHONE,
                android.view.Display.DEFAULT_DISPLAY,
                android.view.Display.DEFAULT_DISPLAY,
                "",
                ActivationSource.MAGICDESK_REQUESTED);
    }

    static DesktopDisplayTarget wired(final int displayId) {
        return new DesktopDisplayTarget(
                Kind.WIRED,
                displayId,
                displayId,
                "",
                ActivationSource.ADOPTED_EXISTING);
    }

    static DesktopDisplayTarget wireless(final int displayId) {
        return new DesktopDisplayTarget(
                Kind.WIRELESS,
                displayId,
                displayId,
                "",
                ActivationSource.ADOPTED_EXISTING);
    }

    static DesktopDisplayTarget simulated(final int displayId) {
        return new DesktopDisplayTarget(
                Kind.SIMULATED,
                displayId,
                displayId,
                "",
                ActivationSource.MAGICDESK_REQUESTED);
    }

    static DesktopDisplayTarget restore(
            final Kind kind,
            final int displayId,
            final int profileDisplayId,
            final String profileKey,
            final ActivationSource activationSource) {
        final DesktopDisplayTarget target = new DesktopDisplayTarget(
                kind,
                displayId,
                displayId,
                "",
                activationSource);
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
                kind,
                displayId,
                newProfileDisplayId,
                newProfileKey,
                activationSource);
    }

    DesktopDisplayTarget withActivationSource(
            final ActivationSource newActivationSource) {
        if (newActivationSource == null) {
            throw new IllegalArgumentException(
                    "activation source is required");
        }
        return new DesktopDisplayTarget(
                kind,
                displayId,
                profileDisplayId,
                profileKey,
                newActivationSource);
    }

    boolean hasProfile() {
        return profileDisplayId > 0 && !profileKey.isEmpty();
    }

}
