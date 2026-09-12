package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopSessionRegistryTest {
    @Test
    public void recreationRetainsRuntimeButCloseAndRestartDoesNot() {
        final DesktopSessionRegistry registry = new DesktopSessionRegistry();
        final DesktopDisplayTarget target = DesktopDisplayTarget.wired(7);
        registry.noteTarget(target);
        final DesktopWorkspaceRuntime first = registry.workspace(7);
        assertTrue(registry.registerHost(7, 42, target, DesktopSessionPolicy.USER));
        registry.unregisterHost(7, true);
        assertSame(first, registry.workspace(7));
        assertFalse(first.isClosed());
        assertTrue(registry.registerHost(7, 42, target, DesktopSessionPolicy.USER));
        registry.close(7);
        assertTrue(first.isClosed());
        registry.noteTarget(target);
        assertTrue(first != registry.workspace(7));
        assertFalse(registry.workspace(7).isClosed());
    }

    @Test
    public void wrongDisplayOrRepeatedUnregisterCannotCloseAnotherWorkspace() {
        final DesktopSessionRegistry registry = new DesktopSessionRegistry();
        final DesktopDisplayTarget target = DesktopDisplayTarget.wired(7);
        registry.noteTarget(target);
        final DesktopWorkspaceRuntime first = registry.workspace(7);
        assertTrue(registry.registerHost(7, 42, target, DesktopSessionPolicy.USER));
        registry.unregisterHost(0, false);
        assertSame(first, registry.workspace(7));
        assertTrue(registry.snapshot(7).hasHost());
        registry.unregisterHost(7, false);
        registry.unregisterHost(7, false);
        assertNull(registry.workspace(7));
        assertTrue(first.isClosed());
    }

    @Test
    public void admittingAnotherDisplayPreservesExistingRuntime() {
        final DesktopSessionRegistry registry = new DesktopSessionRegistry();
        registry.noteTarget(DesktopDisplayTarget.wired(7));
        final DesktopWorkspaceRuntime first = registry.workspace(7);
        registry.noteTarget(DesktopDisplayTarget.phone());
        assertFalse(first.isClosed());
        assertSame(first, registry.workspace(7));
        assertEquals(0, registry.workspace(0).displayId);
    }

    @Test
    public void registryOwnsOneAtomicSessionSnapshot() {
        final DesktopSessionRegistry registry = new DesktopSessionRegistry();
        final DesktopDisplayTarget target = DesktopDisplayTarget.wired(7);

        registry.noteTarget(target);
        assertTrue(registry.registerHost(
                7, 42, target, DesktopSessionPolicy.USER));

        final DesktopSessionSnapshot active = registry.snapshot(7);
        assertTrue(active.hasHost());
        assertSame(target, active.target());
        assertEquals(7, active.activeWorkspaceDisplayId());
        assertEquals(42, active.hostTaskId());

        registry.unregisterHost(7, false);

        final DesktopSessionSnapshot stopped = registry.snapshot(7);
        assertFalse(stopped.hasHost());
        assertNull(stopped.target());
    }

    @Test
    public void closingRegistryClearsPreparedTargetAndHostTogether() {
        final DesktopSessionRegistry registry = new DesktopSessionRegistry();
        registry.noteTarget(DesktopDisplayTarget.wireless(9));
        assertTrue(registry.registerHost(
                9, 51, DesktopDisplayTarget.wireless(9),
                DesktopSessionPolicy.USER));

        registry.close(9);

        assertFalse(registry.snapshot(9).hasHost());
        assertNull(registry.snapshot(9).target());
    }

    @Test
    public void liveHostCannotBeReplacedByAnotherTask() {
        final DesktopSessionRegistry registry = new DesktopSessionRegistry();
        registry.noteTarget(DesktopDisplayTarget.phone());
        assertTrue(registry.registerHost(
                0, 41, DesktopDisplayTarget.phone(),
                DesktopSessionPolicy.USER));

        assertFalse(registry.registerHost(
                0, 42, DesktopDisplayTarget.phone(),
                DesktopSessionPolicy.USER));

        assertEquals(0, registry.snapshot(0).activeWorkspaceDisplayId());
        assertEquals(41, registry.snapshot(0).hostTaskId());
    }

    @Test
    public void sameHostRegistrationIsIdempotent() {
        final DesktopSessionRegistry registry = new DesktopSessionRegistry();
        registry.noteTarget(DesktopDisplayTarget.phone());

        assertTrue(registry.registerHost(
                0, 41, DesktopDisplayTarget.phone(),
                DesktopSessionPolicy.USER));
        assertTrue(registry.registerHost(
                0, 41, DesktopDisplayTarget.phone(),
                DesktopSessionPolicy.USER));
        assertEquals(41, registry.snapshot(0).hostTaskId());
    }

    @Test
    public void sameTaskCannotChangeItsRegisteredTarget() {
        final DesktopSessionRegistry registry = new DesktopSessionRegistry();
        registry.noteTarget(DesktopDisplayTarget.wired(7));
        assertTrue(registry.registerHost(
                7, 41, DesktopDisplayTarget.wired(7),
                DesktopSessionPolicy.USER));

        assertFalse(registry.registerHost(
                7, 41, DesktopDisplayTarget.wireless(7),
                DesktopSessionPolicy.USER));
        assertEquals(
                DesktopDisplayOutput.Kind.WIRED,
                registry.snapshot(7).target().output.kind);
    }

    @Test
    public void hostWithoutPreparedOrResolvedTargetIsRejected() {
        final DesktopSessionRegistry registry = new DesktopSessionRegistry();

        assertFalse(registry.registerHost(
                0, 41, null, DesktopSessionPolicy.USER));
        assertFalse(registry.snapshot(0).hasHost());
    }

    @Test
    public void hostOnAnotherDisplayKeepsBothPreparedTargets() {
        final DesktopSessionRegistry registry = new DesktopSessionRegistry();
        registry.noteTarget(DesktopDisplayTarget.wired(7));

        assertTrue(registry.registerHost(
                0, 41, DesktopDisplayTarget.phone(),
                DesktopSessionPolicy.USER));
        assertFalse(registry.snapshot(7).hasHost());
        assertEquals(7, registry.snapshot(7).target().workspaceDisplayId);
    }
}
