package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class DisplayRecordingSettingsTest {
    @Test
    public void scalesDimensionsWithoutChangingAspectRatio() {
        final DisplayRecordingSettings.Dimensions threeQuarter =
                DisplayRecordingSettings.scaledDimensions(
                        1920, 1080, 75);
        final DisplayRecordingSettings.Dimensions half =
                DisplayRecordingSettings.scaledDimensions(
                        3840, 1200, 50);

        assertEquals(1440, threeQuarter.width);
        assertEquals(810, threeQuarter.height);
        assertEquals(1920, half.width);
        assertEquals(600, half.height);
    }

    @Test
    public void scaledDimensionsAreEvenForEncoderCompatibility() {
        final DisplayRecordingSettings.Dimensions scaled =
                DisplayRecordingSettings.scaledDimensions(
                        1919, 1079, 75);

        assertEquals(1438, scaled.width);
        assertEquals(808, scaled.height);
    }

    @Test
    public void rejectsInvalidSourceDimensions() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DisplayRecordingSettings.scaledDimensions(
                        0, 1080, 50));
    }

    @Test
    public void sanitizesStoredValues() {
        assertEquals(
                DisplayRecordingSettings.DEFAULT_SCALE_PERCENT,
                DisplayRecordingSettings.sanitizeScale(60));
        assertEquals(
                DisplayRecordingSettings.MIN_BITRATE_MBPS,
                DisplayRecordingSettings.sanitizeBitrate(-1));
        assertEquals(
                DisplayRecordingSettings.MAX_BITRATE_MBPS,
                DisplayRecordingSettings.sanitizeBitrate(200));
    }
}
