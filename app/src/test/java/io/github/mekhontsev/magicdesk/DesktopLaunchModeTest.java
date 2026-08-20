package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopLaunchModeTest {
    @Test
    public void mapsNativeFreeformToWindowedAutomationMode() {
        assertEquals("windowed",
                DesktopLaunchMode.semanticWindowingMode("freeform"));
        assertEquals("fullscreen",
                DesktopLaunchMode.semanticWindowingMode("fullscreen"));

        assertTrue(DesktopLaunchMode.matchesWindowingMode(
                "windowed", "freeform"));
        assertTrue(DesktopLaunchMode.matchesWindowingMode(
                "freeform", "freeform"));
        assertFalse(DesktopLaunchMode.matchesWindowingMode(
                "fullscreen", "freeform"));
    }
}
