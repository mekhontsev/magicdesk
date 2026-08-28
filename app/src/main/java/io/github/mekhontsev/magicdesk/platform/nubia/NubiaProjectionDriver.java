package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.DisplayProfileStore;
import io.github.mekhontsev.magicdesk.PlatformProjectionDriver;

import android.app.Activity;
import android.content.Context;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** RedMagic projection implementation backed by the stock firmware services. */
final class NubiaProjectionDriver implements PlatformProjectionDriver {
    @Override
    public boolean hasWirelessConnectionUi(final Context context) {
        return WirelessDisplayController.isAvailable(context);
    }

    @Override
    public boolean openWirelessConnectionUi(final Activity activity) {
        return WirelessDisplayController.openPicker(activity);
    }

    @Override
    public ModeSelection readExternalDisplayModes(
            final Context context,
            final int displayId,
            final String preferredTiming) {
        return convert(NubiaHdmiModeController.readSelection(
                context, displayId, preferredTiming));
    }

    @Override
    public void releaseExternalDisplayMode(final int displayId)
            throws IOException {
        NubiaHdmiModeController.clearSystemModePreference(displayId);
    }

    @Override
    public PreparedMode prepareExternalDisplay(
            final Context context,
            final int physicalDisplayId,
            final DisplayProfileStore.Profile profile) throws IOException {
        final NubiaExternalDisplayModeController.PreparedMode prepared =
                NubiaExternalDisplayModeController.prepare(
                        context, physicalDisplayId, profile);
        return new PreparedMode() {
            @Override
            public int physicalDisplayId() {
                return prepared.physicalDisplayId();
            }

            @Override
            public boolean applyDeferredMode() throws IOException {
                return prepared.applyDeferredMode();
            }

            @Override
            public void close() {
                prepared.close();
            }
        };
    }

    @Override
    public boolean setCaptionTransport(final Transport transport) {
        if (transport == null) {
            throw new IllegalArgumentException("projection transport is required");
        }
        final NubiaCaptionVisibilityManager.Transport nubiaTransport;
        switch (transport) {
            case WIRED:
                nubiaTransport = NubiaCaptionVisibilityManager.Transport.WIRED;
                break;
            case WIRELESS:
                nubiaTransport = NubiaCaptionVisibilityManager.Transport.WIRELESS;
                break;
            case NONE:
            default:
                nubiaTransport = NubiaCaptionVisibilityManager.Transport.NONE;
                break;
        }
        return NubiaCaptionVisibilityManager.setTransport(nubiaTransport);
    }

    private static ModeSelection convert(
            final NubiaHdmiModeController.Selection selection) {
        if (selection == null) {
            return null;
        }
        final List<Mode> modes = new ArrayList<>();
        for (final NubiaHdmiModeController.Mode mode
                : selection.availableModes) {
            modes.add(convert(mode));
        }
        final NubiaHdmiModeController.Selection defaults =
                selection.withPreferredTiming(null);
        return new ModeSelection(
                convert(selection.current),
                convert(selection.target),
                convert(defaults.target),
                modes,
                selection.configurable,
                selection.supportsSystemDefault(),
                selection.isSystemDefaultRequested());
    }

    private static Mode convert(final NubiaHdmiModeController.Mode mode) {
        return mode == null
                ? null : new Mode(mode.timingKey(), mode.displayLabel());
    }

    @Override
    public boolean supportsOutputConfiguration() {
        return true;
    }

}
