package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

public final class FrameworkDisplayCaptureApiTest {
    @Test
    public void captionDownscaleDoesNotLoseLastPixel() {
        assertEquals(95, (int) (690 * (96f / 690)));
        assertEquals(96, (int) (690 * FrameworkDisplayCaptureApi.captureScale(690, 96)));
    }

    @Test
    public void displayAndDiagnosticDimensionsSurviveNativeTruncation() {
        for (int source = 1; source <= 8192; source++) {
            for (final int output : new int[]{1, 16, 96, 160, 1216, 1920, 2688, 8192}) {
                final float scale = FrameworkDisplayCaptureApi.captureScale(source, output);
                assertEquals(output, (int) (source * scale));
                assertEquals(output, (int) ((double) source * scale));
            }
        }
    }

    @Test
    public void invalidDimensionsAreRejectedBeforeCapture() {
        assertThrows(IllegalArgumentException.class,
                () -> FrameworkDisplayCaptureApi.captureScale(0, 96));
        assertThrows(IllegalArgumentException.class,
                () -> FrameworkDisplayCaptureApi.captureScale(690, -1));
    }
}
