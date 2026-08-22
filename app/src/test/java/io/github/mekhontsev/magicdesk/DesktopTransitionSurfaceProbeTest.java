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
                        DisplayCaptureSource.logical(7),
                        1800,
                        500,
                        "desktop-pixel=ff102030\n");

        assertEquals(7, reference.captureSource.logicalDisplayId);
        assertFalse(reference.captureSource.isPhysical());
        assertEquals(1800, reference.x);
        assertEquals(500, reference.y);
        assertEquals(0xFF102030, reference.color);
    }

    @Test
    public void preservesPhysicalCaptureSourceInCommandArguments() {
        final DisplayCaptureSource source = DisplayCaptureSource.parse(
                "p:25,21");
        final DesktopTransitionSurfaceProbe.Reference reference =
                new DesktopTransitionSurfaceProbe.Reference(
                        source, 1800, 500, 0xFF102030);

        assertTrue(source.isPhysical());
        assertEquals(25, source.logicalDisplayId);
        assertEquals("21", source.physicalDisplayId);
        assertEquals("p:25,21 1800 500 ff102030",
                reference.commandArguments());
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
        assertTrue(DesktopTransitionSurfaceProbe.sameColor(
                0xFFE8CDDD, 0xFFE6C8DA));
        assertFalse(DesktopTransitionSurfaceProbe.sameColor(
                0xFF102030, 0xFF303030));
    }

    @Test
    public void recordsExternallyCapturedTransitionSamples() {
        final DesktopTransitionSurfaceProbe.Observation observation =
                DesktopTransitionSurfaceProbe.begin(
                        new DesktopTransitionSurfaceProbe.Reference(
                                DisplayCaptureSource.logical(0),
                                900,
                                500,
                                0xFF102030));

        observation.sample("front", 0xFF102030);
        observation.sample("first-frame", 0xFF606060);
        final DesktopTransitionSurfaceProbe.Result result =
                observation.finish();

        assertTrue(result.surfaceChanged);
        assertEquals(
                "[start:ff102030, front:ff102030, first-frame:ff606060]",
                result.samples.toString());
        assertEquals("", result.error);
    }
}
