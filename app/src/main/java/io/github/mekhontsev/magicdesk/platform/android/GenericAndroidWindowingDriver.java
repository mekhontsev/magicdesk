package io.github.mekhontsev.magicdesk.platform.android;

import io.github.mekhontsev.magicdesk.DeviceSetupManager;
import io.github.mekhontsev.magicdesk.PlatformWindowingDriver;

import java.io.IOException;

/** Standard Android desktop settings without firmware-specific properties. */
final class GenericAndroidWindowingDriver implements PlatformWindowingDriver {
    @Override
    public boolean requiresDesktopInputFocusSynchronization() {
        return false;
    }

    @Override
    public boolean protectsExternalSessionFromPhoneTaskMigration() {
        return false;
    }

    @Override
    public boolean requiresNativeFullscreenCaptionRefresh() {
        return false;
    }

    @Override
    public boolean requiresPhoneTaskRecovery() {
        return false;
    }

    @Override
    public boolean requiresStalePhoneFreeformTaskCleanup() {
        return false;
    }

    @Override
    public String restrictionsPropertyKey() {
        return null;
    }

    @Override
    public String roundedCornersPropertyKey() {
        return null;
    }

    @Override
    public boolean requiresRebootForConfiguration(
            final boolean restrictionsDisabled,
            final boolean roundedCornersDisabled) {
        return false;
    }

    @Override
    public boolean isReady(
            final boolean freeformEnabled,
            final boolean resizableEnabled,
            final boolean restrictionsDisabled,
            final boolean roundedCornersDisabled) {
        return DeviceSetupManager.hasRequiredWindowingSettings(
                freeformEnabled, resizableEnabled);
    }

    @Override
    public void configure(
            final boolean restrictionsDisabled,
            final boolean roundedCornersDisabled) {
    }

    @Override
    public void restoreDefaults() throws IOException {
        // Standard Android settings are restored by DeviceSetupManager.
    }
}
