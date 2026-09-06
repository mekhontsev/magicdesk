package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

/** Keeps all recording settings on the same enabled-state and input path. */
public final class DisplayCapturePanelControllerTest {
    @Test
    public void bitrateKeyboardChangesPersistWithoutTouchCallbacks()
            throws Exception {
        final String source = source();
        final int start = source.indexOf("public void onProgressChanged(");
        final int end = source.indexOf("public void onStartTrackingTouch(", start);
        final String progressCallback = source.substring(start, end);

        assertTrue(progressCallback.contains("if (fromUser)"));
        assertTrue(progressCallback.contains("setBitrate(progress)"));
    }

    @Test
    public void bitrateStepsFollowRecordingSettingsAvailability()
            throws Exception {
        final String source = source();

        assertTrue(source.contains("mBitrateSlider.setEnabled(settingsEnabled)"));
        assertTrue(source.contains("mBitrateDecrease.setEnabled(settingsEnabled)"));
        assertTrue(source.contains("mBitrateIncrease.setEnabled(settingsEnabled)"));
    }

    private static String source() throws Exception {
        return Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/"
                        + "DisplayCapturePanelController.java"));
    }
}
