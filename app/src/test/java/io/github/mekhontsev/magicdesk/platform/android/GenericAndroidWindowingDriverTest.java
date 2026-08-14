package io.github.mekhontsev.magicdesk.platform.android;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import io.github.mekhontsev.magicdesk.PlatformWindowingDriver;

import org.junit.Test;

public final class GenericAndroidWindowingDriverTest {
    @Test
    public void configurationUsesOnlyStandardAndroidSettings() {
        final PlatformWindowingDriver windowing =
                new GenericAndroidWindowingDriver();
        assertTrue(windowing.isReady(true, true, false, false));
        assertFalse(windowing.isReady(true, false, true, true));
    }
}
