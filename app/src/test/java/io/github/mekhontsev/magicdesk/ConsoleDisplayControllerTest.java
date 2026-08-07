package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class ConsoleDisplayControllerTest {
    private static final String DISPLAYS =
            "Display id 0: DisplayInfo{type INTERNAL, "
                    + "uniqueId \"local:4630947168392018835\"}\n"
                    + "Display id 17: DisplayInfo{type EXTERNAL, "
                    + "uniqueId \"local:22082479218605\"}\n";

    @Test
    public void physicalIdIsResolvedForAnyLogicalDisplay() {
        assertEquals(
                "4630947168392018835",
                ConsoleDisplayController.parsePhysicalDisplayId(DISPLAYS, 0));
        assertEquals(
                "22082479218605",
                ConsoleDisplayController.parsePhysicalDisplayId(DISPLAYS, 17));
    }

    @Test
    public void missingLogicalDisplayHasNoPhysicalId() {
        assertNull(ConsoleDisplayController.parsePhysicalDisplayId(DISPLAYS, 3));
        assertNull(ConsoleDisplayController.parsePhysicalDisplayId(null, 0));
    }

    @Test
    public void firstPositiveDisplayIdIgnoresDiagnostics() {
        assertEquals(
                23,
                ConsoleDisplayController.findFirstDisplayId(
                        "unsupported entry\n0\n23\n42\n"));
        assertEquals(
                -1,
                ConsoleDisplayController.findFirstDisplayId(
                        "unsupported entry\n0\n"));
        assertEquals(
                -1,
                ConsoleDisplayController.findFirstDisplayId(null));
    }
}
