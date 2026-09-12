package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopSessionWakeLockTest {
    @Test
    public void holdsOnlyForEnabledDesktopSession() {
        assertFalse(DesktopSessionWakeLock.shouldHold(false, true));
        assertFalse(DesktopSessionWakeLock.shouldHold(true, false));
        assertTrue(DesktopSessionWakeLock.shouldHold(true, true));
        assertTrue(DesktopSessionWakeLock.shouldHold(true, true));
    }
}
