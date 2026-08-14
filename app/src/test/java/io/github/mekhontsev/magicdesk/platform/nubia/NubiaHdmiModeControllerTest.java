package io.github.mekhontsev.magicdesk.platform.nubia;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class NubiaHdmiModeControllerTest {
    private static final String TV_MODES =
            "1920x1080 240 0\n"
                    + "4096x2160 24 4\n"
                    + "3840x2160 60 2\n"
                    + "3840x2160 50 2\n"
                    + "2560x1440 120 0\n"
                    + "1920x1080 120 2\n";

    @Test
    public void nativeChoosesPanelResolutionBeforeRefreshRate() {
        final NubiaHdmiModeController.Selection selection =
                select(null, TV_MODES);

        assertMode(selection.current, 1920, 1080, 240, 0);
        assertMode(selection.target, 3840, 2160, 60, 2);
        assertTrue(selection.configurable);
    }

    @Test
    public void systemFallbackReportsCurrentModeWithoutOfferingChanges() {
        final NubiaHdmiModeController.Mode mode =
                new NubiaHdmiModeController.Mode(1920, 1080, 75, 0);
        final NubiaHdmiModeController.Selection selection =
                NubiaHdmiModeController.systemSelection(
                        mode, "Permission denied");

        assertFalse(selection.configurable);
        assertEquals("Permission denied", selection.detail);
        assertEquals(1, selection.availableModes.size());
        assertSame(mode, selection.current);
        assertSame(mode, selection.target);
        assertSame(selection, selection.withPreferredTiming("3840x2160@60"));
    }

    @Test
    public void publicModesUseSavedTimingAndDefaultToCurrent() {
        final NubiaHdmiModeController.Mode current =
                new NubiaHdmiModeController.Mode(1920, 1080, 60, 0);
        final NubiaHdmiModeController.Mode faster =
                new NubiaHdmiModeController.Mode(1920, 1080, 120, 0);

        final NubiaHdmiModeController.Selection selected =
                NubiaHdmiModeController.systemModeSelection(
                        current,
                        Arrays.asList(current, faster),
                        faster.timingKey());
        final NubiaHdmiModeController.Selection invalid =
                NubiaHdmiModeController.systemModeSelection(
                        current,
                        Arrays.asList(current, faster),
                        "3840x2160@60");

        assertTrue(selected.configurable);
        assertSame(faster, selected.target);
        assertSame(current, invalid.target);
    }

    @Test
    public void mapsOnlyModesReproducedByNubiaResolutionProfiles() {
        final NubiaHdmiModeController.Selection nativeSelection =
                select(null, TV_MODES);
        final NubiaHdmiModeController.Selection fullHdSelection =
                select("1920x1080@240", TV_MODES);
        final NubiaHdmiModeController.Selection cinemaSelection =
                select("4096x2160@24", TV_MODES);

        assertEquals(
                NubiaHdmiModeController.VENDOR_SIZE_2160,
                nativeSelection.vendorSizeType());
        assertEquals(
                NubiaHdmiModeController.VENDOR_SIZE_1080,
                fullHdSelection.vendorSizeType());
        assertMode(cinemaSelection.target, 3840, 2160, 60, 2);
    }

    @Test
    public void vendorListKeepsNativeResolutionAndConsolePresets() {
        final NubiaHdmiModeController.Selection selection = select(
                null,
                "1920x1200 120 0\n"
                        + "1920x1200 60 0\n"
                        + "1920x1080 120 2\n"
                        + "1600x1200 60 0\n"
                        + "1280x720 60 2\n"
                        + "640x480 60 1\n");

        assertEquals(3, selection.availableModes.size());
        assertMode(selection.availableModes.get(0), 1920, 1200, 120, 0);
        assertMode(selection.availableModes.get(1), 1920, 1200, 60, 0);
        assertMode(selection.availableModes.get(2), 1920, 1080, 120, 2);
    }

    @Test
    public void temporaryLowResolutionIsNotOfferedForConsoleMode() {
        final NubiaHdmiModeController.Selection selection = select(
                null,
                "1280x720 60 2\n"
                        + "1920x1080 120 2\n"
                        + "1280x720 60 2\n"
                        + "640x480 60 1\n");

        assertEquals(1, selection.availableModes.size());
        assertMode(selection.availableModes.get(0), 1920, 1080, 120, 2);
        assertMode(selection.target, 1920, 1080, 120, 2);
    }

    @Test
    public void explicitResolutionChoosesItsHighestRefreshRate() {
        final NubiaHdmiModeController.Selection selection = select(
                "2560x1440@120", TV_MODES);

        assertMode(selection.target, 2560, 1440, 120, 0);
    }

    @Test
    public void nativeVitureModeIsDeferredPastNubiaConsoleProfile() {
        final NubiaHdmiModeController.Selection selection = select(
                null,
                "1920x1080 120 2\n"
                        + "1920x1200 120 0\n"
                        + "1920x1200 60 0\n");

        assertMode(selection.current, 1920, 1080, 120, 2);
        assertMode(selection.target, 1920, 1200, 120, 0);
        assertEquals(
                NubiaHdmiModeController.VENDOR_SIZE_UNCHANGED,
                selection.vendorSizeType());
        assertTrue(selection.requiresDeferredVendorMode());
    }

    @Test
    public void vitureFullHdModeUsesNubia1080Profile() {
        final NubiaHdmiModeController.Selection selection = select(
                "1920x1080@120",
                "1920x1200 120 0\n"
                        + "1920x1080 60 0\n"
                        + "1920x1080 120 2\n");

        assertMode(selection.target, 1920, 1080, 120, 2);
        assertEquals(
                NubiaHdmiModeController.VENDOR_SIZE_1080,
                selection.vendorSizeType());
        assertFalse(selection.requiresDeferredVendorMode());
    }

    @Test
    public void unavailableSavedTimingFallsBackToNative() {
        final NubiaHdmiModeController.Selection selection = select(
                "3840x2160@60",
                "1920x1080 120 0\n640x480 60 1\n");

        assertNotNull(selection);
        assertMode(selection.target, 1920, 1080, 120, 0);
    }

    @Test
    public void malformedVendorLinesAreIgnored() {
        final List<NubiaHdmiModeController.Mode> modes =
                NubiaHdmiModeController.parseModes(
                        "garbage\n1920x1080 60 2\n0x1080 60 2\n");

        assertEquals(1, modes.size());
        assertMode(modes.get(0), 1920, 1080, 60, 2);
    }

    @Test
    public void duplicateTimingsUseTheVendorPreferredAspect() {
        final NubiaHdmiModeController.Selection selection = select(
                "1280x720@60",
                "1280x720 60 0\n"
                        + "1280x720 60 2\n");

        assertEquals(1, selection.availableModes.size());
        assertMode(selection.target, 1280, 720, 60, 2);
    }

    private static NubiaHdmiModeController.Selection select(
            final String preferredTiming,
            final String modes) {
        return NubiaHdmiModeController.select(
                preferredTiming,
                NubiaHdmiModeController.parseModes(modes));
    }

    private static void assertMode(
            final NubiaHdmiModeController.Mode mode,
            final int width,
            final int height,
            final int refreshRate,
            final int pictureAspect) {
        assertNotNull(mode);
        assertEquals(width, mode.width);
        assertEquals(height, mode.height);
        assertEquals(refreshRate, mode.refreshRate);
        assertEquals(pictureAspect, mode.pictureAspect);
    }
}
