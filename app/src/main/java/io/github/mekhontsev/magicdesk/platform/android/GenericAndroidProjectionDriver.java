package io.github.mekhontsev.magicdesk.platform.android;

import io.github.mekhontsev.magicdesk.DisplayProfileStore;
import io.github.mekhontsev.magicdesk.PlatformProjectionDriver;

import android.app.Activity;
import android.content.Context;

import java.io.IOException;

/** Standard Android projection entry points without transport ownership. */
final class GenericAndroidProjectionDriver
        implements PlatformProjectionDriver {
    @Override
    public boolean hasWirelessConnectionUi(final Context context) {
        return false;
    }

    @Override
    public boolean openWirelessConnectionUi(final Activity activity) {
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
    public void releaseExternalDisplayMode(final int displayId) {
        // Generic Android does not expose output configuration here.
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

}
