package io.github.mekhontsev.magicdesk.platform.nubia;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.mekhontsev.magicdesk.PlatformWindowingDriver;

import org.junit.Test;

public final class NubiaWindowingDriverTest {
    @Test
    public void configurationRequiresUserAndPrivilegedSettings() {
        final PlatformWindowingDriver windowing = new NubiaWindowingDriver();
        assertTrue(windowing.protectsExternalSessionFromPhoneTaskMigration());
        assertTrue(windowing.isReady(true, true, true, true));
        assertFalse(windowing.isReady(true, true, false, true));
        assertFalse(windowing.isReady(true, true, true, false));
        assertFalse(windowing.isReady(false, true, true, true));
    }
}
