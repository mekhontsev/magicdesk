package io.github.mekhontsev.magicdesk.platform.android;

import io.github.mekhontsev.magicdesk.DisplayProfileStore;
import io.github.mekhontsev.magicdesk.PlatformProjectionDriver;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

import java.io.IOException;

/** Standard Android projection entry points without transport ownership. */
final class GenericAndroidProjectionDriver
        implements PlatformProjectionDriver {
    private static final String[] NO_SETTINGS = new String[0];

    @Override
    public boolean isWirelessDisplayAvailable(final Context context) {
        if (context == null) {
            return false;
        }
        final PackageManager packageManager = context.getPackageManager();
        return packageManager != null
                && new Intent(Settings.ACTION_CAST_SETTINGS)
                        .resolveActivity(packageManager) != null;
    }

    @Override
    public boolean openWirelessDisplayPicker(final Activity activity) {
        if (!isWirelessDisplayAvailable(activity)) {
            return false;
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_CAST_SETTINGS));
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    @Override
    public boolean disconnectWirelessDisplay() {
        return false;
    }

    @Override
    public int activeDesktopDisplayId(final Context context) {
        return -1;
    }

    @Override
    public int waitForDesktopDisplay(final Context context) {
        return -1;
    }

    @Override
    public boolean waitForDesktopStop(final Context context) {
        return true;
    }

    @Override
    public String[] observedSettingKeys() {
        return NO_SETTINGS;
    }

    @Override
    public boolean isMirrorMode() {
        return false;
    }

    @Override
    public boolean requestDesktopMode(final int physicalDisplayId) {
        return false;
    }

    @Override
    public boolean requestMirrorMode() {
        return false;
    }

    @Override
    public ModeSelection readExternalDisplayModes(
            final Context context,
            final int displayId,
            final String preferredTiming) {
        return null;
    }

    @Override
    public PreparedMode prepareExternalDisplay(
            final Context context,
            final int physicalDisplayId,
            final DisplayProfileStore.Profile profile) throws IOException {
        throw new IOException("managed external projection is unavailable");
    }

    @Override
    public boolean setCaptionTransport(final Transport transport) {
        if (transport == null) {
            throw new IllegalArgumentException("transport is required");
        }
        // Standard Android captions do not require transport-specific setup.
        return true;
    }

    @Override
    public boolean supportsOutputConfiguration() {
        return false;
    }

    @Override
    public boolean ownsTransportLifecycle(final Transport transport) {
        return false;
    }
}
