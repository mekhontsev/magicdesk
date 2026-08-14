package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopSessionWakeLockTest {
    @Test
    public void holdsOnlyForEnabledDesktopSession() {
        assertFalse(DesktopSessionWakeLock.shouldHold(false, 3));
        assertFalse(DesktopSessionWakeLock.shouldHold(true, -1));
        assertTrue(DesktopSessionWakeLock.shouldHold(true, 0));
        assertTrue(DesktopSessionWakeLock.shouldHold(true, 3));
    }
}
