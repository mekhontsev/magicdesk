package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.io.IOException;
import java.util.function.BooleanSupplier;

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

    static boolean resetDefaults(final Context context) throws IOException {
        return resetDefaults(SystemDesktopModeSetting.access(context),
                () -> {
                    final PlatformProjectionDriver.DesktopOption option =
                            PlatformDrivers.current().projection().desktopOption();
                    final boolean platformSaved = option == null || option.reset();
                    return MagicDeskSettings.resetCompatibilityOptions() && platformSaved;
                });
    }

    static boolean resetDefaults(
            final SystemDesktopModeSetting.Access system,
            final BooleanSupplier resetOverrides) throws IOException {
        final boolean systemChanged = SystemDesktopModeSetting.reset(system);
        if (!resetOverrides.getAsBoolean()) {
            throw new IOException("Android desktop mode was reset, but compatibility preferences could not be saved");
        }
        return systemChanged;
    }
}
