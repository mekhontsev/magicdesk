package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.graphics.Rect;

import org.junit.Test;

public final class TaskRepositoryCommandTest {
    @Test
    public void boundsUseWindowContainerTransactionCommand() {
        final Rect bounds = rect(10, 20, 810, 620);
        assertEquals(
                AppProcessCommand.run(
                        "io.github.mekhontsev.magicdesk.TaskWindowingCommand",
                        "bounds 3 42 10 20 810 620"),
                TaskRepository.createBoundsTransactionCommand(
                        3, 42, bounds));
    }

    @Test
    public void boundsRejectEmptyRectangle() {
        assertThrows(
                IllegalArgumentException.class,
                () -> TaskRepository.createBoundsTransactionCommand(
                        3, 42, rect(10, 20, 10, 620)));
    }

    private static Rect rect(
            final int left,
            final int top,
            final int right,
            final int bottom) {
        // android.jar constructors are stubs in local JVM tests.
        final Rect bounds = new Rect();
        bounds.left = left;
        bounds.top = top;
        bounds.right = right;
        bounds.bottom = bottom;
        return bounds;
    }
}
