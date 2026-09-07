package io.github.mekhontsev.magicdesk;

/** Physical input selected for capture; independent of the virtual pointer. */
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

    public boolean isEnabled() {
        return keyboard || mouse;
    }

    public String diagnosticDetail() {
        return "keyboard=" + keyboard + ", mouse=" + mouse;
    }
}
