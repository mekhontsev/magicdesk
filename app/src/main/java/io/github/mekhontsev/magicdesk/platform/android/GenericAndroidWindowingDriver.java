package io.github.mekhontsev.magicdesk.platform.android;

import io.github.mekhontsev.magicdesk.PlatformWindowingDriver;

import java.io.IOException;

/** Standard Android desktop settings without firmware-specific properties. */
final class GenericAndroidWindowingDriver implements PlatformWindowingDriver {
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
            final boolean restrictionsDisabled,
            final boolean roundedCornersDisabled) {
        return true;
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
