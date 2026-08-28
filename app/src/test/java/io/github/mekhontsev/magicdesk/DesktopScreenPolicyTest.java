package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopScreenPolicyTest {
    @Test
    public void phoneScreenControlRequiresExternalDesktopSession() {
        assertFalse(DesktopScreenPolicy.isExternalDesktop(0));
        assertTrue(DesktopScreenPolicy.isExternalDesktop(7));
        assertFalse(DesktopScreenPolicy.isExternalDesktopSession(0, 0));
        assertFalse(DesktopScreenPolicy.isExternalDesktopSession(7, -1));
        assertTrue(DesktopScreenPolicy.isExternalDesktopSession(7, 7));
        assertFalse(DesktopScreenPolicy.isExternalDesktopSession(7, 8));

        assertFalse(DesktopScreenPolicy.canControlPhoneScreen(
                false, DesktopDisplayTarget.wireless(7), true, true));
        assertFalse(DesktopScreenPolicy.canControlPhoneScreen(
                true, DesktopDisplayTarget.wireless(7), false, true));
        assertFalse(DesktopScreenPolicy.canControlPhoneScreen(
                true, DesktopDisplayTarget.wireless(7), true, false));
        assertFalse(DesktopScreenPolicy.canControlPhoneScreen(
                true, DesktopDisplayTarget.simulated(7), true, true));
        assertTrue(DesktopScreenPolicy.canControlPhoneScreen(
                true, DesktopDisplayTarget.wireless(7), true, true));
        assertFalse(DesktopScreenPolicy.canControlPhoneScreen(
                true, DesktopDisplayTarget.phone(), true, true));
        assertFalse(DesktopScreenPolicy.canControlPhoneScreen(
                true, null, true, true));
    }
}
