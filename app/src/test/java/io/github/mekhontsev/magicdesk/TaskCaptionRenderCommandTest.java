package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TaskCaptionRenderCommandTest {
    @Test
    public void resolvesTaskLocalCaptionToDisplayCoordinates() {
        final TaskLocalInsetsSourceParser.CaptionSource source =
                new TaskLocalInsetsSourceParser.CaptionSource(
                        1,
                        new TaskLocalInsetsSourceParser.Frame(
                                0, 0, 800, 48));
        final TaskLocalInsetsSourceParser.Frame window =
                new TaskLocalInsetsSourceParser.Frame(
                        100, 200, 900, 900);

        final TaskLocalInsetsSourceParser.Frame sample =
                TaskCaptionRenderCommand.captureFrame(source, window);

        assertEquals(500, sample.left);
        assertEquals(208, sample.top);
        assertEquals(899, sample.right);
        assertEquals(240, sample.bottom);
    }

    @Test
    public void parsesVariedAndUniformObservations() throws Exception {
        final TaskCaptionRenderCommand.Observation varied =
                TaskCaptionRenderCommand.parseObservation(
                        "caption-render=varied sample=2x1"
                                + " dominant=ff303030 contrast=192"
                                + " contrasting=1/2"
                                + " signature=333eee"
                                + " crop=[500,208][899,240]\n");
        final TaskCaptionRenderCommand.Observation uniform =
                TaskCaptionRenderCommand.parseObservation(
                        "caption-render=uniform sample=2x1"
                                + " dominant=ff303030 contrast=0"
                                + " contrasting=0/2"
                                + " signature=333333"
                                + " crop=[500,208][899,240]\n");

        assertTrue(varied.visuallyVaried);
        assertEquals(1, varied.contrastingPixels);
        assertFalse(uniform.visuallyVaried);
    }

    @Test
    public void validatesSampleAgainstResolvedCaption() {
        final TaskLocalInsetsSourceParser.CaptionSource source =
                new TaskLocalInsetsSourceParser.CaptionSource(
                        1,
                        new TaskLocalInsetsSourceParser.Frame(
                                0, 0, 800, 48));
        final TaskLocalInsetsSourceParser.Frame window =
                new TaskLocalInsetsSourceParser.Frame(
                        100, 200, 900, 900);

        TaskCaptionRenderCommand.validateSampleFrame(
                source, window,
                rect(500, 208, 899, 240));
    }

    private static android.graphics.Rect rect(
            final int left,
            final int top,
            final int right,
            final int bottom) {
        final android.graphics.Rect bounds = new android.graphics.Rect();
        bounds.left = left;
        bounds.top = top;
        bounds.right = right;
        bounds.bottom = bottom;
        return bounds;
    }
}
