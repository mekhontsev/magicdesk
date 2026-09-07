package io.github.mekhontsev.magicdesk;

import java.io.IOException;

/** Firmware-specific part of desktop windowing provisioning. */
public interface PlatformWindowingDriver {
    String restrictionsPropertyKey();

    String roundedCornersPropertyKey();

    boolean requiresRebootForConfiguration(
            boolean restrictionsDisabled,
            boolean roundedCornersDisabled);

    boolean isReady(
            boolean freeformEnabled,
            boolean resizableEnabled,
            boolean restrictionsDisabled,
            boolean roundedCornersDisabled);

    void configure(
            boolean restrictionsDisabled,
            boolean roundedCornersDisabled) throws IOException;

    void restoreDefaults() throws IOException;
}
