package io.github.mekhontsev.magicdesk;

import java.io.IOException;

/** Persistent desktop-windowing properties required by ZTE/nubia firmware. */
final class NubiaWindowingDriver implements PlatformWindowingDriver {
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
