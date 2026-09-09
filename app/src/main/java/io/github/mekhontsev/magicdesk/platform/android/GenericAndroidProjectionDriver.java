package io.github.mekhontsev.magicdesk.platform.android;

import io.github.mekhontsev.magicdesk.CompatibilityDiagnostics;
import io.github.mekhontsev.magicdesk.DisplayProfileStore;
import io.github.mekhontsev.magicdesk.PlatformProjectionDriver;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import java.io.IOException;

/** Standard Android projection entry points without transport ownership. */
final class GenericAndroidProjectionDriver
        implements PlatformProjectionDriver {
    @Override
    public boolean hasWirelessConnectionUi(final Context context) {
        return context != null && new Intent(Settings.ACTION_CAST_SETTINGS)
                .resolveActivity(context.getPackageManager()) != null;
    }

    @Override
    public boolean openWirelessConnectionUi(final Activity activity) {
        if (activity == null || !hasWirelessConnectionUi(activity)) {
            return false;
        }
        try {
            activity.startActivity(new Intent(Settings.ACTION_CAST_SETTINGS));
            return true;
        } catch (ActivityNotFoundException | SecurityException error) {
            CompatibilityDiagnostics.record(
                    "WIRELESS-DISPLAY-003",
                    "Could not open Android cast settings",
                    error.getMessage(), error);
            return false;
        }
    }

    @Override
    public ModeSelection readExternalDisplayModes(
            final Context context,
            final int displayId,
            final String preferredTiming) {
        return null;
    }

    @Override
    public void releaseExternalDisplayMode(final int displayId) {
        // Generic Android does not expose output configuration here.
    }

    @Override
    public void prepareExternalDisplay(
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

}
