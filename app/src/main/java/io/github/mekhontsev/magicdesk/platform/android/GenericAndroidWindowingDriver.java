package io.github.mekhontsev.magicdesk.platform.android;

import io.github.mekhontsev.magicdesk.PlatformWindowingDriver;

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
    public boolean configure(
            final boolean restrictionsDisabled,
            final boolean roundedCornersDisabled) {
        return false;
    }

    @Override
    public void restoreDefaults() {
        // Standard Android settings are restored by DeviceSetupManager.
    }
}
