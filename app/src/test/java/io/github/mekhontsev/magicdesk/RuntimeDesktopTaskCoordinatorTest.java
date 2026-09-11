package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class RuntimeDesktopTaskCoordinatorTest {
    @Test
    public void oldWorkspaceCannotReleaseActiveOrPreparedReplacement() {
        final DesktopWorkspaceRuntime old =
                new DesktopWorkspaceRuntime(DesktopDisplayTarget.wired(7));
        for (DesktopDisplayTarget target : new DesktopDisplayTarget[] {
                DesktopDisplayTarget.wired(7), DesktopDisplayTarget.phone()}) {
            final DesktopWorkspaceRuntime next = new DesktopWorkspaceRuntime(target);
            assertFalse(RuntimeDesktopTaskCoordinator.canReleaseWorkspace(old, next, next));
            assertFalse(RuntimeDesktopTaskCoordinator.canReleaseWorkspace(old, old, next));
            assertFalse(RuntimeDesktopTaskCoordinator.canReleaseWorkspace(old, null, next));
        }
        assertTrue(RuntimeDesktopTaskCoordinator.canReleaseWorkspace(old, old, old));
        old.close();
        assertTrue(RuntimeDesktopTaskCoordinator.canReleaseWorkspace(old, old, null));
        assertTrue(RuntimeDesktopTaskCoordinator.canReleaseWorkspace(old, null, null));
        assertFalse(RuntimeDesktopTaskCoordinator.canReleaseWorkspace(null, null, null));
    }

    @Test
    public void disablesTaskRuntimeWithoutShell() {
        final DesktopSessionSnapshot session = DesktopSessionSnapshot.empty()
                .noteTarget(DesktopDisplayTarget.wired(7))
                .registerHost(7, 42);

        assertEquals(RuntimeDesktopTaskCoordinator.Mode.DISABLED,
                RuntimeDesktopTaskCoordinator.modeFor(session, false));
    }

    @Test
    public void observesTasksWhileShellIsReadyWithoutDesktopHost() {
        assertEquals(RuntimeDesktopTaskCoordinator.Mode.OBSERVING,
                RuntimeDesktopTaskCoordinator.modeFor(
                        DesktopSessionSnapshot.empty(), true));
    }

    @Test
    public void activatesTaskRuntimeForPhoneOrExternalDesktop() {
        final DesktopSessionSnapshot phone = DesktopSessionSnapshot.empty()
                .noteTarget(DesktopDisplayTarget.phone())
                .registerHost(0, 41);
        final DesktopSessionSnapshot external = DesktopSessionSnapshot.empty()
                .noteTarget(DesktopDisplayTarget.wired(7))
                .registerHost(7, 42);

        assertEquals(RuntimeDesktopTaskCoordinator.Mode.ACTIVE,
                RuntimeDesktopTaskCoordinator.modeFor(phone, true));
        assertEquals(RuntimeDesktopTaskCoordinator.Mode.ACTIVE,
                RuntimeDesktopTaskCoordinator.modeFor(external, true));
    }

    @Test
    public void preparedTargetWithoutHostRemainsObservationOnly() {
        final DesktopSessionSnapshot session = DesktopSessionSnapshot.empty()
                .noteTarget(DesktopDisplayTarget.wireless(7));

        assertEquals(RuntimeDesktopTaskCoordinator.Mode.OBSERVING,
                RuntimeDesktopTaskCoordinator.modeFor(session, true));
    }
}
