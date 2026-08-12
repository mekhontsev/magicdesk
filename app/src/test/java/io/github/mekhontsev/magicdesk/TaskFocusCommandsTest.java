package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public final class TaskFocusCommandsTest {
    @Test
    public void createsOrderedShellFocusCommands() {
        assertEquals(
                AppProcessCommand.run(
                        "io.github.mekhontsev.magicdesk.TaskWindowingCommand",
                        "focus 9 17 42"),
                TaskFocusCommands.createShellCommand(
                        9, Arrays.asList(17, 42)));
    }

    @Test
    public void rejectsEmptyTaskList() {
        assertInvalid(9, Collections.<Integer>emptyList());
    }

    @Test
    public void rejectsInvalidTaskId() {
        assertInvalid(9, Arrays.asList(17, -1));
    }

    @Test
    public void rejectsInvalidDisplayId() {
        assertInvalid(-1, Arrays.asList(17));
    }

    private static void assertInvalid(
            final int displayId,
            final Iterable<Integer> taskIds) {
        try {
            TaskFocusCommands.createShellCommand(displayId, taskIds);
            fail("invalid task IDs accepted");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
