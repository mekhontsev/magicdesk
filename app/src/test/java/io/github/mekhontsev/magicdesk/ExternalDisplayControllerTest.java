package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class ExternalDisplayControllerTest {
    private static final String DISPLAYS =
            "Display id 0: DisplayInfo{type INTERNAL, "
                    + "uniqueId \"local:4630947168392018835\"}\n"
                    + "Display id 17: DisplayInfo{type EXTERNAL, "
                    + "uniqueId \"local:22082479218605\"}\n";

    @Test
    public void physicalIdIsResolvedForAnyLogicalDisplay() {
        assertEquals(
                "4630947168392018835",
                ExternalDisplayController.parsePhysicalDisplayId(DISPLAYS, 0));
        assertEquals(
                "22082479218605",
                ExternalDisplayController.parsePhysicalDisplayId(DISPLAYS, 17));
    }

    @Test
    public void missingLogicalDisplayHasNoPhysicalId() {
        assertNull(ExternalDisplayController.parsePhysicalDisplayId(DISPLAYS, 3));
        assertNull(ExternalDisplayController.parsePhysicalDisplayId(null, 0));
    }

    @Test
    public void wirelessUniqueIdIsResolved() {
        final String output = "Display id 8: DisplayInfo{type WIFI, "
                + "uniqueId \"wifi:aa:bb:cc\"}\n";

        assertEquals(
                "wifi:aa:bb:cc",
                ExternalDisplayController.parseDisplayUniqueId(output, 8));
    }

    @Test
    public void firstPositiveDisplayIdIgnoresDiagnostics() {
        assertEquals(
                23,
                ExternalDisplayController.findFirstDisplayId(
                        "unsupported entry\n0\n23\n42\n"));
        assertEquals(
                -1,
                ExternalDisplayController.findFirstDisplayId(
                        "unsupported entry\n0\n"));
        assertEquals(
                -1,
                ExternalDisplayController.findFirstDisplayId(null));
    }
}
