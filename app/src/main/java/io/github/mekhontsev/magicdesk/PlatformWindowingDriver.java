package io.github.mekhontsev.magicdesk;

import java.io.IOException;

/** Firmware-specific part of desktop windowing provisioning. */
interface PlatformWindowingDriver {
    boolean requiresMirrorInputFocusSynchronization();

    /** Whether native freeform-to-fullscreen transitions leave a stale client caption inset. */
    boolean requiresNativeFullscreenCaptionRefresh();

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
