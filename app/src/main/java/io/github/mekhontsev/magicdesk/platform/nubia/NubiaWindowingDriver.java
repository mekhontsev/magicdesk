package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.DeviceSetupManager;
import io.github.mekhontsev.magicdesk.PlatformWindowingDriver;

import java.io.IOException;

/** Persistent desktop-windowing properties required by Nubia firmware. */
final class NubiaWindowingDriver implements PlatformWindowingDriver {
    @Override
    public boolean requiresDesktopInputFocusSynchronization() {
        return true;
    }

    @Override
    public boolean protectsExternalSessionFromPhoneTaskMigration() {
        // Starting an already running desktop task from Nubia's phone launcher
        // can tear down the external desktop task hierarchy instead of moving
        // only that task. Reject the migration while the session is active
        // and normalize system-driven moves that bypass the launch callback.
        return true;
    }

    @Override
    public boolean requiresNativeFullscreenCaptionRefresh() {
        // Nubia removes the server-side caption source without relaying the
        // removal to every application client, leaving a caption-height strip.
        return true;
    }

    @Override
    public boolean requiresTaskActivationSurfaceFence() {
        // A direct task reorder can update framework focus while leaving the
        // previous freeform Surface above it. A fresh application WindowState
        // forces Nubia's compositor hierarchy to settle before the reorder.
        return true;
    }

    @Override
    public boolean requiresPhoneTaskRecovery() {
        // Nubia can retain moved or removed tasks in WMShell's desktop
        // repository, which destabilizes Quickstep after returning to Home.
        return true;
    }

    @Override
    public boolean requiresStalePhoneFreeformTaskCleanup() {
        // Nubia Quickstep can bind a stale DesktopTaskView after a phone-side
        // freeform task has already disappeared.
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
