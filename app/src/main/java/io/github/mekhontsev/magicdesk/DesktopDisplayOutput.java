package io.github.mekhontsev.magicdesk;

/** Selected output and its preferences, independent of task residency. */
final class DesktopDisplayOutput {
    enum Kind { PHONE, WIRED, WIRELESS, SIMULATED }

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
    final String profileKey;
    final ActivationSource activationSource;

    DesktopDisplayOutput(final Kind kind, final int displayId,
            final String profileKey, final ActivationSource activationSource) {
        if (kind == null || activationSource == null
                || (kind == Kind.PHONE ? displayId != 0 : displayId <= 0)) {
            throw new IllegalArgumentException("invalid desktop output");
        }
        this.kind = kind;
        this.displayId = displayId;
        this.profileKey = profileKey == null ? "" : profileKey;
        this.activationSource = activationSource;
    }

    DesktopDisplayOutput withProfile(final String key) {
        if (displayId <= 0 || key == null || key.isEmpty()) {
            throw new IllegalArgumentException("invalid display profile");
        }
        return new DesktopDisplayOutput(kind, displayId, key, activationSource);
    }

    DesktopDisplayOutput withActivationSource(final ActivationSource source) {
        return new DesktopDisplayOutput(kind, displayId, profileKey, source);
    }

    boolean hasProfile() {
        return displayId > 0 && !profileKey.isEmpty();
    }

    boolean sameEndpoint(final DesktopDisplayOutput other) {
        return other != null && displayId == other.displayId && kind == other.kind;
    }
}
