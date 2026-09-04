package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

public final class DesktopTaskTransferTest {
    @Test
    public void freeformTransferUsesAtomicTaskCommand() {
        final Rect bounds = bounds(20, 30, 800, 900);
        final String command = DesktopTaskTransfer.createFreeformCommand(
                42,
                0,
                3,
                bounds,
                null,
                DesktopTaskDensity.UNCHANGED);

        assertTrue(command.contains(
                "TaskDisplayAreaLaunchCommand move 42 0 3 "
                        + "20 30 800 900 -1"));
    }

    @Test
    public void freeformTransferCarriesResolvedDensity() {
        final String command = DesktopTaskTransfer.createFreeformCommand(
                42,
                0,
                3,
                bounds(20, 30, 800, 900),
                null,
                200);

        assertTrue(command.contains(
                "TaskDisplayAreaLaunchCommand move 42 0 3 "
                        + "20 30 800 900 200"));
    }

    @Test
    public void transferRequiresDifferentDisplays() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DesktopTaskTransfer.createFreeformCommand(
                        42,
                        3,
                        3,
                        bounds(20, 30, 800, 900),
                        null,
                        DesktopTaskDensity.UNCHANGED));
    }

    private static Rect bounds(
            final int left,
            final int top,
            final int right,
            final int bottom) {
        final Rect result = new Rect();
        result.left = left;
        result.top = top;
        result.right = right;
        result.bottom = bottom;
        return result;
    }
}
