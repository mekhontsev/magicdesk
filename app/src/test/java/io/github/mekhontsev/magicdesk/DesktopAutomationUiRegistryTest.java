package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.List;

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

    @Test
    public void identitySegmentsPreserveCaseWhitespaceAndSeparators() {
        final var ids = new HashSet<String>();
        for (final String identity : List.of(
                "", "item", " ", "  ", "A", "a", "a ", " a",
                "/Desktop/File.desktop", "/desktop/file.desktop",
                "a/b", "a-b", "a b", "a~002fb", "a%b",
                "\ud83d\ude80", "\ud83d", "\ufffd")) {
            assertTrue(identity, ids.add(
                    DesktopAutomationUiRegistry.identitySegment(identity)));
        }
        assertEquals("com.example.App", DesktopAutomationUiRegistry.identitySegment(
                "com.example.App"));
        assertEquals("a~002fb", DesktopAutomationUiRegistry.identitySegment("a/b"));
        assertEquals("a~007e002fb", DesktopAutomationUiRegistry.identitySegment("a~002fb"));
    }
}
