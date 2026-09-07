package io.github.mekhontsev.magicdesk;

/** Resolves preferences once at HOME acquisition; consumers reuse that lease. */
final class DesktopCompatibilitySettings {
    private DesktopCompatibilitySettings() {
    }

    static DesktopCompatibilityPolicy nextSession() {
        return MagicDeskSettings.load().compatibilityPolicy(
                PlatformDrivers.current().features());
    }

    static DesktopCompatibilityPolicy current() {
        final DesktopHomeRoleLease.State lease = DesktopHomeRoleLease.snapshot();
        return lease == null ? nextSession() : lease.compatibility;
    }
}
