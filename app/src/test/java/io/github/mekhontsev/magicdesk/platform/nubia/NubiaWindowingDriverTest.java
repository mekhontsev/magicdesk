package io.github.mekhontsev.magicdesk.platform.nubia;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.mekhontsev.magicdesk.PlatformWindowingDriver;

import org.junit.Test;

public final class NubiaWindowingDriverTest {
    @Test
    public void configurationRequiresBothFirmwareProperties() {
        final PlatformWindowingDriver windowing = new NubiaWindowingDriver();
        assertTrue(windowing.isReady(true, true));
        assertFalse(windowing.isReady(false, true));
        assertFalse(windowing.isReady(true, false));
        assertFalse(windowing.isReady(false, false));
    }
}
