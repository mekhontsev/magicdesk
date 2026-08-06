package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

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
    }

    @Test
    public void explicitResolutionChoosesItsHighestRefreshRate() {
        final NubiaHdmiModeController.Selection selection = select(
                "2560x1440@120", TV_MODES);

        assertMode(selection.target, 2560, 1440, 120, 0);
    }

    @Test
    public void nativePreservesVitureBeast120HzMode() {
        final NubiaHdmiModeController.Selection selection = select(
                null,
                "1920x1200 120 0\n1920x1200 120 0\n");

        assertMode(selection.current, 1920, 1200, 120, 0);
        assertMode(selection.target, 1920, 1200, 120, 0);
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
                "1920x1080 60 2\n"
                        + "1280x720 60 0\n"
                        + "1280x720 60 2\n");

        assertEquals(2, selection.availableModes.size());
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
