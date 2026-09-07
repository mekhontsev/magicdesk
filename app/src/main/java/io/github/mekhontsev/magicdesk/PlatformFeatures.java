package io.github.mekhontsev.magicdesk;

/** Capability groups owned by a firmware platform rather than a display. */
public final class PlatformFeatures {
    public final boolean wiredDesktop;
    public final boolean wirelessDesktop;
    public final DesktopInputRelayPolicy defaultInputRelay;
    public final boolean vendorHardware;

    public PlatformFeatures(
            final boolean wiredDesktop,
            final boolean wirelessDesktop,
            final DesktopInputRelayPolicy defaultInputRelay,
            final boolean vendorHardware) {
        this.wiredDesktop = wiredDesktop;
        this.wirelessDesktop = wirelessDesktop;
        this.defaultInputRelay = defaultInputRelay == null
                ? DesktopInputRelayPolicy.NONE : defaultInputRelay;
        this.vendorHardware = vendorHardware;
    }

    public boolean supportsDisplay(final DesktopDisplayTarget.Kind kind) {
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

    public boolean supportsExternalDesktop() {
        return wiredDesktop || wirelessDesktop;
    }
}
