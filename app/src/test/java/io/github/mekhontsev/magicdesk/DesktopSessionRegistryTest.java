package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopSessionRegistryTest {
    @Test
    public void registryOwnsOneAtomicSessionSnapshot() {
        final DesktopSessionRegistry registry = new DesktopSessionRegistry();
        final DesktopDisplayTarget target = DesktopDisplayTarget.wired(7);

        registry.noteTarget(target);
        assertTrue(registry.registerHost(
                7, 42, target, DesktopSessionPolicy.USER));

        final DesktopSessionSnapshot active = registry.snapshot();
        assertTrue(active.hasHost());
        assertSame(target, active.target());
        assertEquals(7, active.activeDisplayId());
        assertEquals(42, active.hostTaskId());

        registry.unregisterHost(7, false);

        final DesktopSessionSnapshot stopped = registry.snapshot();
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

        registry.close();

        assertFalse(registry.snapshot().hasHost());
        assertNull(registry.snapshot().target());
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

        assertEquals(0, registry.snapshot().activeDisplayId());
        assertEquals(41, registry.snapshot().hostTaskId());
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
        assertEquals(41, registry.snapshot().hostTaskId());
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
                DesktopDisplayTarget.Kind.WIRED,
                registry.snapshot().target().kind);
    }

    @Test
    public void hostWithoutPreparedOrResolvedTargetIsRejected() {
        final DesktopSessionRegistry registry = new DesktopSessionRegistry();

        assertFalse(registry.registerHost(
                0, 41, null, DesktopSessionPolicy.USER));
        assertFalse(registry.snapshot().hasHost());
    }

    @Test
    public void hostCannotReplaceAnotherPreparedTarget() {
        final DesktopSessionRegistry registry = new DesktopSessionRegistry();
        registry.noteTarget(DesktopDisplayTarget.wired(7));

        assertFalse(registry.registerHost(
                0, 41, DesktopDisplayTarget.phone(),
                DesktopSessionPolicy.USER));
        assertFalse(registry.snapshot().hasHost());
        assertEquals(7, registry.snapshot().target().displayId);
    }
}
