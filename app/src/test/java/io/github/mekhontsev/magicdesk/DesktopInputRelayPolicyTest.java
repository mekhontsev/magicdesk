package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopInputRelayPolicyTest {
    @Test
    public void keepsKeyboardAndMouseCapabilitiesIndependent() {
        final DesktopInputRelayPolicy keyboard =
                new DesktopInputRelayPolicy(true, false);
        final DesktopInputRelayPolicy mouse =
                new DesktopInputRelayPolicy(false, true);

        assertTrue(keyboard.keyboard);
        assertFalse(keyboard.mouse);
        assertFalse(mouse.keyboard);
        assertTrue(mouse.mouse);

    }

    @Test
    public void emptyPolicyDoesNotCapturePhysicalDevices() {
        assertFalse(DesktopInputRelayPolicy.NONE.isEnabled());
        assertTrue(DesktopInputRelayPolicy.KEYBOARD_AND_MOUSE.isEnabled());
    }

}
