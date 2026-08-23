package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DesktopAutomationUiRegistryTest {
    @Test
    public void segmentProducesStableSafeIds() {
        assertEquals("taskbar.start",
                DesktopAutomationUiRegistry.segment("Taskbar.Start"));
        assertEquals("open-files-here",
                DesktopAutomationUiRegistry.segment(" Open Files Here "));
        assertEquals("item",
                DesktopAutomationUiRegistry.segment("  "));
    }
}
