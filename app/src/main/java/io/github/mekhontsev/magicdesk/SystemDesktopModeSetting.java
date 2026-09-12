package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.provider.Settings;

import java.io.IOException;

/** Optional Android policy, independent of MagicDesk's required provisioning. */
final class SystemDesktopModeSetting {
    private static final String KEY = "force_desktop_mode_on_external_displays";

    interface Access {
        boolean read() throws IOException;

        boolean canChange();

        void write(boolean enabled) throws IOException;

        void reset() throws IOException;
    }

    private SystemDesktopModeSetting() {
    }

    static boolean read(final Context context) throws IOException {
        try {
            return Settings.Global.getInt(context.getContentResolver(), KEY, 0) != 0;
        } catch (RuntimeException error) {
            throw new IOException("could not read Android desktop mode", error);
        }
    }

    static boolean canChange() {
        return ShellAccess.isReady() && !DesktopRuntimeBridge.hasWorkspaces()
                && DesktopHomeRoleLease.snapshot() == null;
    }

    static boolean setEnabled(final Context context, final boolean enabled) throws IOException {
        return setEnabled(access(context), enabled);
    }

    static Access access(final Context context) {
        return new Access() {
            @Override
            public boolean read() throws IOException {
                return SystemDesktopModeSetting.read(context);
            }

            @Override
            public boolean canChange() {
                return SystemDesktopModeSetting.canChange();
            }

            @Override
            public void write(final boolean value) throws IOException {
                ShellAccess.run(writeCommand(value));
            }

            @Override
            public void reset() throws IOException {
                ShellAccess.run(resetCommand());
            }
        };
    }

    static boolean setEnabled(final Access access, final boolean enabled) throws IOException {
        if (access.read() == enabled) {
            return false;
        }
        if (!access.canChange()) {
            throw new IOException("close Desktop and connect the privileged service before changing Android desktop mode");
        }
        // Android owns the value. Do not save a second preference or mark the
        // required setup incomplete for an optional, user-requested change.
        access.write(enabled);
        if (access.read() != enabled) {
            throw new IOException("Android desktop mode did not retain the requested value");
        }
        return true;
    }

    static boolean reset(final Access access) throws IOException {
        if (!access.canChange()) {
            throw new IOException("close Desktop and connect the privileged service before resetting Android desktop mode");
        }
        final boolean wasEnabled = access.read();
        // Remove even an explicit false override; Android remains the only
        // value store and absence resolves to its disabled default.
        access.reset();
        if (access.read()) {
            throw new IOException("Android desktop mode did not retain its default value");
        }
        return wasEnabled;
    }

    static String writeCommand(final boolean enabled) {
        return "/system/bin/settings put global " + KEY + (enabled ? " 1" : " 0");
    }

    static String resetCommand() {
        return "/system/bin/settings delete global " + KEY;
    }
}
