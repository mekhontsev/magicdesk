package io.github.mekhontsev.magicdesk;

import java.io.IOException;

/** Standard Android desktop settings without firmware-specific properties. */
final class GenericAndroidWindowingDriver implements PlatformWindowingDriver {
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
