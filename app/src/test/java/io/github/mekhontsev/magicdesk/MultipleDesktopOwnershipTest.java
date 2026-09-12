package io.github.mekhontsev.magicdesk;

import java.util.Map;
import java.util.Set;

import org.junit.Test;

import static org.junit.Assert.*;

public final class MultipleDesktopOwnershipTest {
    @Test public void closingOneHostRetainsTheOthersIdentityAndTask() {
        final var registry = new DesktopSessionRegistry();
        final var phone = DesktopDisplayTarget.phone();
        final var external = DesktopDisplayTarget.simulated(7);
        registry.noteTarget(phone);
        registry.noteTarget(external);
        assertTrue(registry.registerHost(0, 10, phone, DesktopSessionPolicy.USER));
        assertTrue(registry.registerHost(7, 20, external, DesktopSessionPolicy.USER));
        final var survivor = registry.workspace(7);
        final var published = registry.snapshot(7);
        assertEquals(survivor.id, published.workspace().id);
        assertNotEquals(registry.workspace(0).id, survivor.id);
        registry.close(0);
        assertSame(survivor, registry.workspace(7));
        assertEquals(survivor.id, published.workspace().id);
        assertEquals(20, registry.snapshot(7).hostTaskId());
        assertEquals(1, registry.snapshots().size());
        registry.unregisterHost(7, true);
        assertTrue(registry.registerHost(7, 21, external, DesktopSessionPolicy.USER));
        assertSame(survivor, registry.workspace(7));
    }

    @Test public void isolatedTestsNeverJoinUserWorkspaces() {
        final var registry = new DesktopSessionRegistry();
        registry.noteTarget(DesktopDisplayTarget.phone());
        assertThrows(IllegalStateException.class, () -> registry.noteTarget(
                DesktopDisplayTarget.simulated(7), DesktopSessionPolicy.ISOLATED_SELF_TEST));
        assertEquals(1, registry.snapshots().size());
    }

    @Test public void failedAdmissionWithALateHostCanBeRetired() {
        final var registry = new DesktopSessionRegistry();
        final var target = DesktopDisplayTarget.simulated(7);
        registry.noteTarget(target);
        registry.registerHost(7, 20, target, DesktopSessionPolicy.USER);
        final var failed = registry.workspace(7);
        registry.clearTarget(target);
        assertTrue(failed.isClosed());
        assertNull(registry.workspace(7));
        registry.noteTarget(target);
        assertNotEquals(failed.id, registry.workspace(7).id);
    }

    @Test public void inputStaysOnPreparedOwnerUntilSelectedDesktopIsReady() {
        final var input = new DisplayInputTarget();
        input.reconcile(Map.of(0, "phone"));
        input.prepared(0, "phone");
        input.reconcile(Map.of(0, "phone", 7, "external"));
        assertEquals(0, input.readyTarget());
        assertTrue(input.desktopShortcuts());
        assertThrows(IllegalStateException.class, () -> input.select(7));
        input.prepared(7, "external");
        assertEquals(7, input.readyTarget());
        input.select(0);
        assertFalse(input.release(7));
        input.reconcile(Map.of(0, "phone"));
        assertEquals(0, input.readyTarget());
        assertTrue(input.desktopShortcuts());
    }

    @Test public void staleReadinessCannotControlAReusedDisplayId() {
        final var input = new DisplayInputTarget();
        input.reconcile(Map.of(7, "first"));
        input.prepared(7, "first");
        input.reconcile(Map.of(7, "second"));
        assertEquals(-1, input.readyTarget());
        input.prepared(7, "first");
        assertEquals(-1, input.readyTarget());
        input.prepared(7, "second");
        assertEquals(7, input.readyTarget());
    }

    @Test public void toolsRequireAnExplicitDestinationWithMultipleDesktops() {
        final var desktops = Set.of(0, 7);
        assertThrows(IllegalStateException.class, () -> ToolLaunchTarget.resolve("auto", -1, desktops));
        assertTrue(ToolLaunchTarget.resolve("auto", 0, desktops).desktop);
        assertTrue(ToolLaunchTarget.resolve("auto", 7, desktops).desktop);
        assertFalse(ToolLaunchTarget.resolve("auto", 9, desktops).desktop);
    }

    @Test public void phoneNormalizationHasOneOwnerAndNeverTouchesPhoneDesktop() {
        final var membership = new ShellWorkspaceMembership();
        membership.update(Set.of(7, 9));
        assertTrue(membership.ownsPhoneNormalization(7));
        assertFalse(membership.ownsPhoneNormalization(9));
        membership.update(Set.of(0, 7, 9));
        assertTrue(membership.hasPhoneDesktop());
        assertFalse(membership.ownsPhoneNormalization(7));
        assertFalse(membership.ownsPhoneNormalization(9));
        membership.update(Set.of(9));
        assertTrue(membership.ownsPhoneNormalization(9));
        membership.update(Set.of());
        assertFalse(membership.ownsPhoneNormalization(9));
    }
}
