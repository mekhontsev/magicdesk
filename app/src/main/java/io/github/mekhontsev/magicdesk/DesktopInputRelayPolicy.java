package io.github.mekhontsev.magicdesk;

/** Platform policy for relaying physical input through MagicDesk devices. */
public final class DesktopInputRelayPolicy {
    public static final DesktopInputRelayPolicy NONE =
            new DesktopInputRelayPolicy(false, false);
    public static final DesktopInputRelayPolicy KEYBOARD_AND_MOUSE =
            new DesktopInputRelayPolicy(true, true);

    public final boolean keyboard;
    public final boolean mouse;

    public DesktopInputRelayPolicy(
            final boolean keyboard,
            final boolean mouse) {
        this.keyboard = keyboard;
        this.mouse = mouse;
    }

    public boolean isRequired() {
        return keyboard || mouse;
    }

    public DesktopInputRelayPolicy merge(
            final DesktopInputRelayPolicy extension) {
        if (extension == null || !extension.isRequired()) {
            return this;
        }
        return new DesktopInputRelayPolicy(
                keyboard || extension.keyboard,
                mouse || extension.mouse);
    }

    public String diagnosticDetail() {
        return "keyboard=" + keyboard + ", mouse=" + mouse;
    }
}
