package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertSame;

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
    public void emptyPolicyDoesNotCapturePhysicalDevices() {
        assertFalse(DesktopInputRelayPolicy.NONE.isEnabled());
        assertTrue(DesktopInputRelayPolicy.KEYBOARD_AND_MOUSE.isEnabled());
    }

    @Test
    public void unsetPreferenceFollowsPlatformDefault() {
        assertSame(DesktopInputRelayPolicy.NONE, DesktopInputRelayPolicy.resolve(
                null, DesktopInputRelayPolicy.NONE));
        assertSame(DesktopInputRelayPolicy.KEYBOARD_AND_MOUSE,
                DesktopInputRelayPolicy.resolve(
                        null, DesktopInputRelayPolicy.KEYBOARD_AND_MOUSE));
    }

    @Test
    public void userCanOverrideEitherPlatformDefault() {
        assertSame(DesktopInputRelayPolicy.NONE, DesktopInputRelayPolicy.resolve(
                false, DesktopInputRelayPolicy.KEYBOARD_AND_MOUSE));
        assertSame(DesktopInputRelayPolicy.KEYBOARD_AND_MOUSE,
                DesktopInputRelayPolicy.resolve(true, DesktopInputRelayPolicy.NONE));
    }
}
