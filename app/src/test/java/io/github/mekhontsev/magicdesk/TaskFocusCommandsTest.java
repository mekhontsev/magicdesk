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
                "/system/bin/am task focus 17 && /system/bin/am task focus 42",
                TaskFocusCommands.createShellCommand(Arrays.asList(17, 42)));
    }

    @Test
    public void rejectsEmptyTaskList() {
        assertInvalid(Collections.<Integer>emptyList());
    }

    @Test
    public void rejectsInvalidTaskId() {
        assertInvalid(Arrays.asList(17, -1));
    }

    private static void assertInvalid(final Iterable<Integer> taskIds) {
        try {
            TaskFocusCommands.createShellCommand(taskIds);
            fail("invalid task IDs accepted");
        } catch (IllegalArgumentException expected) {
            // Expected.
        }
    }
}
