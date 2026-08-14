package io.github.mekhontsev.magicdesk;

/** Capability groups owned by a firmware platform rather than a display. */
final class PlatformFeatures {
    final boolean wiredDesktop;
    final boolean wirelessDesktop;
    final boolean externalInputBridge;
    final boolean vendorHardware;
    final boolean internalAudioCapture;

    PlatformFeatures(
            final boolean wiredDesktop,
            final boolean wirelessDesktop,
            final boolean externalInputBridge,
            final boolean vendorHardware,
            final boolean internalAudioCapture) {
        this.wiredDesktop = wiredDesktop;
        this.wirelessDesktop = wirelessDesktop;
        this.externalInputBridge = externalInputBridge;
        this.vendorHardware = vendorHardware;
        this.internalAudioCapture = internalAudioCapture;
    }

    boolean supportsDisplay(final DesktopDisplayTarget.Kind kind) {
        if (kind == null) {
            return false;
        }
        switch (kind) {
            case PHONE:
            case SIMULATED:
                return true;
            case WIRED:
                return wiredDesktop;
            case WIRELESS:
                return wirelessDesktop;
            default:
                return false;
        }
    }

    boolean supportsExternalDesktop() {
        return wiredDesktop || wirelessDesktop;
    }
}
