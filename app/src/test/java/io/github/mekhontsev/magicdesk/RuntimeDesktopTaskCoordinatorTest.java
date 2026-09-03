package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class RuntimeDesktopTaskCoordinatorTest {
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
