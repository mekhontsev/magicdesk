package io.github.mekhontsev.magicdesk.platform.android;

import static org.junit.Assert.assertFalse;

import io.github.mekhontsev.magicdesk.PlatformWindowingDriver;

import org.junit.Test;

public final class GenericAndroidWindowingDriverTest {
    @Test
    public void configurationMakesNoFirmwareChanges() {
        final PlatformWindowingDriver windowing =
                new GenericAndroidWindowingDriver();
        assertFalse(windowing.configure(false, false));
        assertFalse(windowing.configure(true, true));
        windowing.restoreDefaults();
    }
}
