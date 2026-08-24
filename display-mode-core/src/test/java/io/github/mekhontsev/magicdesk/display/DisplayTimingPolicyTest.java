package io.github.mekhontsev.magicdesk.display;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.util.List;

public final class DisplayTimingPolicyTest {
    @Test
    public void choosesNativeResolutionBeforeRefreshRate() {
        final List<DisplayTimingPolicy.ParsedTiming> modes =
                DisplayTimingPolicy.normalize(
                        DisplayTimingPolicy.parseNubiaModes(
                                "1920x1080 240 0\n"
                                        + "4096x2160 24 4\n"
                                        + "3840x2160 60 2\n"
                                        + "2560x1440 120 0\n"));

        final DisplayTiming selected = DisplayTimingPolicy.bestNative(modes);

        assertNotNull(selected);
        assertEquals(3840, selected.width());
        assertEquals(2160, selected.height());
        assertEquals(60, selected.refreshRate());
        assertEquals(2, selected.pictureAspect());
    }

    @Test
    public void choosesUltrawideAtNativePanelHeight() {
        final List<DisplayTimingPolicy.ParsedTiming> modes =
                DisplayTimingPolicy.normalize(
                        DisplayTimingPolicy.parseNubiaModes(
                                "1920x1080 75 0\n"
                                        + "2560x1080 60 0\n"
                                        + "2560x1080 75 0\n"));

        final DisplayTiming selected = DisplayTimingPolicy.bestNative(modes);

        assertNotNull(selected);
        assertEquals("2560x1080@75", selected.timingKey());
        assertEquals("2560 1080 75 0", selected.vendorValue());
    }

    @Test
    public void ignoresMalformedAndDeduplicatesVendorModes() {
        final List<DisplayTimingPolicy.ParsedTiming> modes =
                DisplayTimingPolicy.normalize(
                        DisplayTimingPolicy.parseNubiaModes(
                                "garbage\n"
                                        + "1280x720 60 0\n"
                                        + "1280x720 60 2\n"
                                        + "0x720 60 2\n"));

        assertEquals(1, modes.size());
        assertEquals(2, modes.get(0).pictureAspect());
    }
}
