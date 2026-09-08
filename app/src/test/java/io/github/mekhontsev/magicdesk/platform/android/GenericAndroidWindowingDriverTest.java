package io.github.mekhontsev.magicdesk.platform.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.mekhontsev.magicdesk.PlatformWindowingDriver;

import org.junit.Test;

public final class GenericAndroidWindowingDriverTest {
    @Test
    public void configurationAddsNoFirmwareRequirements() {
        final PlatformWindowingDriver windowing =
                new GenericAndroidWindowingDriver();
        assertTrue(windowing.isReady(false, false));
        assertTrue(windowing.isReady(true, true));
        assertFalse(windowing.requiresRebootForConfiguration(false, false));
    }
}
