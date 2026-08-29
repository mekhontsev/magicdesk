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

        final DesktopInputRelayPolicy combined = keyboard.merge(mouse);
        assertTrue(combined.keyboard);
        assertTrue(combined.mouse);
    }

    @Test
    public void emptyPolicyDoesNotRequireInputRouting() {
        assertFalse(DesktopInputRelayPolicy.NONE.isRequired());
        assertTrue(DesktopInputRelayPolicy.KEYBOARD_AND_MOUSE.isRequired());
    }
}
