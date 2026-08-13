package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopTransitionSurfaceProbeTest {
    @Test
    public void parsesCapturedPixelReference() throws Exception {
        final DesktopTransitionSurfaceProbe.Reference reference =
                DesktopTransitionSurfaceProbe.parseReference(
                        7, 1800, 500, "desktop-pixel=ff102030\n");

        assertEquals(7, reference.displayId);
        assertEquals(1800, reference.x);
        assertEquals(500, reference.y);
        assertEquals(0xFF102030, reference.color);
    }

    @Test
    public void parsesTransitionObservation() throws Exception {
        final String output = "transition-surface-changed=true\n"
                + "transition-pixels=start:ff102030,before:ff102030,"
                + "settled:ff777777\n";

        assertTrue(DesktopTransitionSurfaceProbe
                .parseReportedSurfaceChange(output));
        assertEquals(
                "start:ff102030,before:ff102030,settled:ff777777",
                DesktopTransitionSurfaceProbe.parseReportedSamples(output));
        assertFalse(DesktopTransitionSurfaceProbe
                .parseReportedSurfaceChange(
                        "transition-surface-changed=false\n"));
    }

    @Test
    public void parsesOptionalProbeError() {
        assertEquals(
                "capture unavailable",
                DesktopTransitionSurfaceProbe.parseReportedError(
                        "transition-probe-error=capture unavailable\n"));
        assertEquals(
                "",
                DesktopTransitionSurfaceProbe.parseReportedError(
                        "transition-surface-changed=false\n"));
    }

    @Test
    public void comparesPixelColorsWithSmallCaptureTolerance() {
        assertTrue(DesktopTransitionSurfaceProbe.sameColor(
                0xFF102030, 0xFF14242C));
        assertFalse(DesktopTransitionSurfaceProbe.sameColor(
                0xFF102030, 0xFF303030));
    }
}
