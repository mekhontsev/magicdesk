package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.provider.Settings;
import java.io.IOException;

/** User-requested system policy; neither a Desktop lease nor an app preference. */
final class SystemChildProcessSetting {
    interface Access {
        boolean monitoringEnabled() throws IOException;
        void setMonitoringEnabled(boolean enabled) throws IOException;
    }

    private SystemChildProcessSetting() {}

    static boolean readDisabled(final Context context) throws IOException {
        // Resolve Android's property/default and flag support in the privileged process;
        // the app's public ContentResolver owns Settings.Global access and attribution.
        final boolean fallback = ShellAccess.readChildProcessMonitoringDefault();
        try {
            return !monitoringEnabled(Settings.Global.getString(context.getContentResolver(),
                    FrameworkChildProcessApi.KEY), fallback);
        } catch (RuntimeException error) {
            throw new IOException("could not read Android child process monitoring", error);
        }
    }

    static boolean setDisabled(final Context context, final boolean disabled) throws IOException {
        return setDisabled(new Access() {
            @Override public boolean monitoringEnabled() throws IOException {
                return !readDisabled(context);
            }
            @Override public void setMonitoringEnabled(final boolean enabled) throws IOException {
                ShellAccess.run(writeCommand(enabled));
            }
        }, disabled);
    }

    static boolean monitoringEnabled(final String override, final boolean fallback) {
        // Same precedence and boolean-string semantics as FeatureFlagUtils.isEnabled.
        return override == null || override.isEmpty() ? fallback : Boolean.parseBoolean(override);
    }

    static String writeCommand(final boolean enabled) {
        return "/system/bin/settings put global " + FrameworkChildProcessApi.KEY + " " + enabled;
    }

    static boolean setDisabled(final Access access, final boolean disabled) throws IOException {
        final boolean monitoring = !disabled;
        if (access.monitoringEnabled() == monitoring) return false;
        access.setMonitoringEnabled(monitoring);
        if (access.monitoringEnabled() != monitoring) {
            throw new IOException("Android did not retain the requested child process monitoring setting");
        }
        return true;
    }
}
