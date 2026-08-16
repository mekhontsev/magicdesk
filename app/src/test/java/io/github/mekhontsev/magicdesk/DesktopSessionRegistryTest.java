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
        registry.registerHost(7, 42, false);

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
        registry.registerHost(9, 51, false);

        registry.close();

        assertFalse(registry.snapshot().hasHost());
        assertNull(registry.snapshot().target());
    }
}
