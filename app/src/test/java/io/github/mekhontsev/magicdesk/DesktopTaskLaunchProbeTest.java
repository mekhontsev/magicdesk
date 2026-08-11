package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import java.io.IOException;

import org.junit.Test;

public final class DesktopTaskLaunchProbeTest {
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
