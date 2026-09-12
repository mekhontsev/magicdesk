package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import org.junit.Test;

public final class DesktopSessionEndPlanTest {
    @Test public void everyCloseReturnsItsOwnTasks() {
        final var target = DesktopDisplayTarget.wired(7);
        final var current = DesktopSessionSnapshot.empty().noteTarget(target).registerHost(7, 42);
        for (final var mode : DesktopCloseMode.values()) {
            final var plan = DesktopSessionEndPlan.create(current.workspace(), target, mode, true);
            assertSame(target, plan.workspace);
            assertEquals(mode, plan.destination);
            assertEquals(plan.destination == DesktopCloseMode.EXIT
                    ? DesktopSessionEndPlan.Tasks.RETURN_TO_DEFAULT
                    : DesktopSessionEndPlan.Tasks.RETURN_TO_DEFAULT_AND_REMEMBER, plan.tasks);
            assertTrue(plan.needsPhoneRecovery());
            assertTrue(plan.recoverPhoneTasks);
        }
    }

    @Test public void phoneClosePreservesTasksWithoutExternalRecovery() {
        final var target = DesktopDisplayTarget.phone();
        final var plan = DesktopSessionEndPlan.create(DesktopWorkspaceSnapshot.empty(),
                target, DesktopCloseMode.HOME, false);
        assertEquals(plan.destination == DesktopCloseMode.EXIT
                    ? DesktopSessionEndPlan.Tasks.RETURN_TO_DEFAULT
                    : DesktopSessionEndPlan.Tasks.RETURN_TO_DEFAULT_AND_REMEMBER, plan.tasks);
        assertFalse(plan.needsPhoneRecovery());
        assertFalse(plan.recoverPhoneTasks);
    }

    @Test public void lostHostCanFinishCloseThroughTheRetainedTarget() {
        final var target = DesktopDisplayTarget.simulated(7);
        final var current = DesktopSessionSnapshot.empty().noteTarget(target)
                .registerHost(7, 42).unregisterHost(7, false);
        assertSame(target, DesktopSessionEndPlan.create(current.workspace(), target,
                DesktopCloseMode.CONTROL_PANEL, true).workspace);
    }

    @Test(expected = IllegalStateException.class)
    public void staleCloseCannotReleaseAnotherWorkspacesSessionResources() {
        final var current = DesktopSessionSnapshot.empty().noteTarget(DesktopDisplayTarget.wired(8));
        DesktopSessionEndPlan.create(current.workspace(), DesktopDisplayTarget.wired(7),
                DesktopCloseMode.HOME, true);
    }

    @Test(expected = IllegalStateException.class)
    public void retainedHostIdentityStillRejectsWrongCloseAfterAdmissionCleared() {
        final var target = DesktopDisplayTarget.wired(8);
        final var current = DesktopSessionSnapshot.empty().noteTarget(target)
                .registerHost(8, 42).clearTarget(target);
        DesktopSessionEndPlan.create(current.workspace(), DesktopDisplayTarget.wired(7),
                DesktopCloseMode.HOME, true);
    }
}
