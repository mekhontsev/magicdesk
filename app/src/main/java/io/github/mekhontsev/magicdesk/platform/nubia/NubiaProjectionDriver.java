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
    public void prepareExternalDisplay(
            final Context context,
            final int physicalDisplayId,
            final DisplayProfileStore.Profile profile) throws IOException {
        if (profile != null && profile.resetOutputModePending) {
            // Relinquish only a mode owned by MagicDesk; later System/native
            // starts must leave the user's system-selected timing untouched.
            releaseExternalDisplayMode(physicalDisplayId);
            profile.resetOutputModePending = false;
            DisplayProfileStore.save(profile);
        }
        final NubiaHdmiModeController.Selection selection =
                NubiaHdmiModeController.readSelection(
                        context,
                        physicalDisplayId,
                        profile == null ? null : profile.outputTiming);
        NubiaHdmiModeController.applyIfNeeded(
                context, physicalDisplayId, selection);
    }

    @Override
    public boolean setCaptionTransports(final java.util.Set<Transport> transports) {
        final java.util.Set<NubiaCaptionVisibilityManager.Transport> targets =
                java.util.EnumSet.noneOf(NubiaCaptionVisibilityManager.Transport.class);
        for (final Transport transport : transports) {
            switch (transport) {
                case WIRED -> targets.add(NubiaCaptionVisibilityManager.Transport.WIRED);
                case WIRELESS -> targets.add(NubiaCaptionVisibilityManager.Transport.WIRELESS);
                default -> { }
            }
        }
        return NubiaCaptionVisibilityManager.setTransports(targets);
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
