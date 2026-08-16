package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.DeviceSetupManager;
import io.github.mekhontsev.magicdesk.PlatformWindowingDriver;

import java.io.IOException;

/** Persistent desktop-windowing properties required by Nubia firmware. */
final class NubiaWindowingDriver implements PlatformWindowingDriver {
    @Override
    public boolean requiresMirrorInputFocusSynchronization() {
        return true;
    }

    @Override
    public boolean protectsExternalSessionFromPhoneTaskMigration() {
        // Starting an already running desktop task from Nubia's phone launcher
        // can remove the entire NubiaAppMirrorDisplay instead of moving only
        // that task. The shell observer rejects that migration while the
        // external session is active. The observer also normalizes any
        // system-driven freeform move that bypasses the launch callback.
        return true;
    }

    @Override
    public boolean requiresNativeFullscreenCaptionRefresh() {
        // Nubia removes the server-side caption source without relaying the
        // removal to every application client, leaving a caption-height strip.
        return true;
    }

    @Override
    public String restrictionsPropertyKey() {
        return NubiaDesktopPropertyManager.Property.DEVICE_RESTRICTIONS.key;
    }

    @Override
    public String roundedCornersPropertyKey() {
        return NubiaDesktopPropertyManager.Property.ROUNDED_CORNERS.key;
    }

    @Override
    public boolean requiresRebootForConfiguration(
            final boolean restrictionsDisabled,
            final boolean roundedCornersDisabled) {
        return !restrictionsDisabled || !roundedCornersDisabled;
    }

    @Override
    public boolean isReady(
            final boolean freeformEnabled,
            final boolean resizableEnabled,
            final boolean restrictionsDisabled,
            final boolean roundedCornersDisabled) {
        return DeviceSetupManager.hasRequiredWindowingSettings(
                freeformEnabled, resizableEnabled)
                && restrictionsDisabled
                && roundedCornersDisabled;
    }

    @Override
    public void configure(
            final boolean restrictionsDisabled,
            final boolean roundedCornersDisabled) throws IOException {
        if (!restrictionsDisabled) {
            NubiaDesktopPropertyManager.write(
                    NubiaDesktopPropertyManager.Property.DEVICE_RESTRICTIONS,
                    "false");
        }
        if (!roundedCornersDisabled) {
            NubiaDesktopPropertyManager.write(
                    NubiaDesktopPropertyManager.Property.ROUNDED_CORNERS,
                    "false");
        }
    }

    @Override
    public void restoreDefaults() throws IOException {
        NubiaDesktopPropertyManager.write(
                NubiaDesktopPropertyManager.Property.DEVICE_RESTRICTIONS,
                "");
        NubiaDesktopPropertyManager.write(
                NubiaDesktopPropertyManager.Property.ROUNDED_CORNERS,
                "");
    }
}
