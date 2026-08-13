package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

public final class DisplayPixelProbeTest {
    @Test
    public void rejectsUniformRegion() {
        final int[] pixels = new int[96 * 16];
        Arrays.fill(pixels, 0xFF303030);

        assertFalse(DisplayPixelProbe.analyze(
                pixels, 96, 16).visuallyVaried);
    }

    @Test
    public void acceptsVisibleCaptionControls() {
        final int[] pixels = new int[96 * 16];
        Arrays.fill(pixels, 0xFF303030);
        Arrays.fill(pixels, 400, 430, 0xFFE0E0E0);

        assertTrue(DisplayPixelProbe.analyze(
                pixels, 96, 16).visuallyVaried);
    }

    @Test
    public void ignoresIsolatedPixelAndSubtleNoise() {
        final int[] pixels = new int[96 * 16];
        Arrays.fill(pixels, 0xFF303030);
        pixels[100] = 0xFFFFFFFF;
        Arrays.fill(pixels, 200, 240, 0xFF383838);

        assertFalse(DisplayPixelProbe.analyze(
                pixels, 96, 16).visuallyVaried);
    }

    @Test
    public void comparesCaptionAgainstHeterogeneousDesktop() {
        final int[] desktop = new int[96 * 16];
        for (int i = 0; i < desktop.length; i++) {
            desktop[i] = (i & 1) == 0 ? 0xFF205080 : 0xFF80B040;
        }
        final int[] transparentCaption = Arrays.copyOf(
                desktop, desktop.length);
        Arrays.fill(transparentCaption, 400, 430, 0xFFE0E0E0);
        final int[] opaqueCaption = new int[desktop.length];
        Arrays.fill(opaqueCaption, 0xFF303030);
        Arrays.fill(opaqueCaption, 400, 430, 0xFFE0E0E0);

        final DisplayPixelProbe.RegionSignature reference =
                DisplayPixelProbe.analyze(
                        desktop, 96, 16).signature;
        final DisplayPixelProbe.RegionDifference transparentDifference =
                DisplayPixelProbe.analyze(
                        transparentCaption, 96, 16)
                        .signature.compare(reference);
        final DisplayPixelProbe.RegionDifference opaqueDifference =
                DisplayPixelProbe.analyze(
                        opaqueCaption, 96, 16)
                        .signature.compare(reference);

        assertFalse(transparentDifference.materiallyDifferent);
        assertTrue(opaqueDifference.materiallyDifferent);
    }

    @Test
    public void roundTripsCompactRegionSignature() {
        final int[] pixels = {
                0xFF123456, 0xFFABCDEF
        };
        final DisplayPixelProbe.RegionSignature signature =
                DisplayPixelProbe.analyze(pixels, 2, 1).signature;
        final DisplayPixelProbe.RegionSignature decoded =
                DisplayPixelProbe.RegionSignature.decode(
                        2, 1, signature.encode());

        assertEquals(0, decoded.compare(signature).changedPixels);
    }
}
