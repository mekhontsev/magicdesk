package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import org.junit.Test;

public final class DesktopTaskLaunchProbeTest {
    @Test
    public void newWindowIgnoresFrontCallbacksFromExistingTasks() throws Exception {
        verifyTaskIdentity("""
                check(!acceptsTaskId(10, -1, existing), "accepted an old task");
                check(acceptsTaskId(12, -1, existing), "rejected a new task");
                check(!acceptsTaskId(-1, -1, existing), "accepted an invalid task");
                """);
    }

    @Test
    public void exactTaskObservationStillAcceptsExistingTaskMoves() throws Exception {
        verifyTaskIdentity("""
                check(acceptsTaskId(10, 10, existing), "rejected the expected task");
                check(!acceptsTaskId(12, 10, existing), "accepted another task");
                """);
    }

    private static void verifyTaskIdentity(final String scenario) throws Exception {
        RuntimeSourceFixture.verify(RuntimeSourceFixture.methods(
                "DesktopTaskLaunchObserverCommand", "acceptsTaskId")
                + "public static void verify() {\n"
                + "final Set<Integer> existing = Set.of(10, 11);\n" + scenario + "}\n");
    }

    @Test
    public void parsesObservedTaskState() throws Exception {
        final DesktopTaskLaunchProbe.Observation observation =
                DesktopTaskLaunchProbe.parseObservation(
                        "MAGICDESK_TASK_LAUNCH_OBSERVED"
                                + "\t42\t7\t5\t100\t120\t900\t720");

        assertEquals(42, observation.taskId);
        assertEquals(7, observation.displayId);
        assertEquals(5, observation.windowingMode);
        assertEquals(100, observation.left);
        assertEquals(120, observation.top);
        assertEquals(900, observation.right);
        assertEquals(720, observation.bottom);
    }

    @Test(expected = IOException.class)
    public void rejectsMalformedObservation() throws Exception {
        DesktopTaskLaunchProbe.parseObservation(
                "MAGICDESK_TASK_LAUNCH_OBSERVED\tbroken");
    }

    @Test(expected = IOException.class)
    public void surfacesObserverFailure() throws Exception {
        DesktopTaskLaunchProbe.parseObservation(
                "MAGICDESK_TASK_LAUNCH_OBSERVED\terror\treflection failed");
    }
}
