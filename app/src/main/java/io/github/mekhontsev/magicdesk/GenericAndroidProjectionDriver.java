package io.github.mekhontsev.magicdesk;

import android.app.Activity;
import android.content.Context;

import java.io.IOException;

/** No-op projection implementation for the local Generic Android profile. */
final class GenericAndroidProjectionDriver
        implements PlatformProjectionDriver {
    private static final String[] NO_SETTINGS = new String[0];

    @Override
    public boolean isWirelessDisplayAvailable(final Context context) {
        return false;
    }

    @Override
    public boolean openWirelessDisplayPicker(final Activity activity) {
        return false;
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
        throw new IOException("external projection is unsupported");
    }

    @Override
    public boolean setCaptionTransport(final Transport transport) {
        return transport == Transport.NONE;
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
