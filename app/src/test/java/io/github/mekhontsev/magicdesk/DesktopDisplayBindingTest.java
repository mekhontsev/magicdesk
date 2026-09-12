package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Distinct IDs expose role mixups without exercising an unimplemented output backend. */
public final class DesktopDisplayBindingTest {
    private static DesktopDisplayTarget binding(final int workspace, final int output) {
        return DesktopDisplayTarget.restore(DesktopDisplayOutput.Kind.WIRED,
                workspace, output, "display:wired:monitor",
                DesktopDisplayOutput.ActivationSource.ADOPTED_EXISTING);
    }

    @Test
    public void allProductionFactoriesRemainDirect() {
        for (final DesktopDisplayTarget target : new DesktopDisplayTarget[] {
                DesktopDisplayTarget.phone(), DesktopDisplayTarget.wired(3),
                DesktopDisplayTarget.wireless(4), DesktopDisplayTarget.simulated(5)}) {
            target.requireSupportedBinding();
            assertEquals(target.workspaceDisplayId, target.output.displayId);
        }
    }

    @Test
    public void hostAndInputBelongToWorkspaceNotOutput() {
        final DesktopDisplayTarget target = binding(12, 7);
        final DesktopSessionSnapshot pending = DesktopSessionSnapshot.empty().noteTarget(target);
        assertEquals(-1, pending.activeWorkspaceDisplayId());
        assertEquals(-1, pending.activeOutputDisplayId());
        assertNull(pending.targetForWorkspace(7));
        final DesktopSessionSnapshot active = pending.registerHost(12, 42);
        assertEquals(12, active.activeWorkspaceDisplayId());
        assertEquals(12, active.activeWorkspaceDisplayId());
        assertEquals(7, active.activeOutputDisplayId());
        assertSame(target, active.targetForWorkspace(12));
        assertTrue(target.ownsWorkspace(12));
        assertFalse(target.ownsWorkspace(7));
        assertTrue(target.usesOutput(7));
        assertFalse(target.usesOutput(12));
        assertEquals(12, DesktopCaptureTarget.sourceFor(target).logicalDisplayId);
    }

    @Test(expected = IllegalStateException.class)
    public void outputCannotRegisterAsWorkspaceHost() {
        DesktopSessionSnapshot.empty().noteTarget(binding(12, 7)).registerHost(7, 42);
    }

    @Test
    public void closeClearsAllActiveRolesAndConfigurationChangeRetainsBinding() {
        final DesktopDisplayTarget target = binding(12, 7);
        final DesktopSessionSnapshot active = DesktopSessionSnapshot.empty()
                .noteTarget(target).registerHost(12, 42);
        final DesktopSessionSnapshot detached = active.unregisterHost(12, true);
        assertSame(target, detached.target());
        assertEquals(-1, detached.activeWorkspaceDisplayId());
        assertEquals(-1, detached.activeOutputDisplayId());
        assertNull(active.close().target());
        assertEquals(-1, active.close().activeWorkspaceDisplayId());
    }

    @Test
    public void outputChangesCannotMasqueradeAsTheSameSession() {
        final DesktopDisplayTarget target = binding(12, 7);
        final DesktopDisplayTarget otherOutput = binding(12, 8);
        final DesktopSessionRegistry registry = new DesktopSessionRegistry();
        registry.noteTarget(target);
        assertFalse(registry.registerHost(12, 42, otherOutput, DesktopSessionPolicy.USER));
        assertTrue(registry.registerHost(12, 42, target, DesktopSessionPolicy.USER));
        assertFalse(registry.registerHost(12, 42, otherOutput, DesktopSessionPolicy.USER));
        registry.clearTarget(otherOutput);
        assertSame(target, registry.snapshot(12).target());
    }

    @Test
    public void homeSelectionUsesWorkspaceWhileBrightnessUsesOutput() {
        final DesktopDisplayTarget target = binding(0, 7);
        assertTrue(target.isDefaultWorkspace());
        assertTrue(DesktopSessionSnapshot.empty().noteTarget(target).isLocalActiveOrStarting());
        assertEquals(DesktopHomeSurfaceRouter.Surface.DESKTOP,
                DesktopHomeSurfaceRouter.forWorkspaces(java.util.List.of(target)).primary);
        assertTrue(DesktopAdaptiveBrightnessController.shouldDisable(true, target));
        final DesktopDisplayTarget phoneOutput = DesktopDisplayTarget.restore(
                DesktopDisplayOutput.Kind.BUILT_IN, 12, 0, "",
                DesktopDisplayOutput.ActivationSource.MAGICDESK_REQUESTED);
        assertEquals(DesktopHomeSurfaceRouter.Surface.LAUNCHER,
                DesktopHomeSurfaceRouter.forWorkspaces(java.util.List.of(phoneOutput)).primary);
        assertFalse(DesktopAdaptiveBrightnessController.shouldDisable(true, phoneOutput));
    }

    @Test
    public void preferencesDoNotChangeEitherDisplayIdentity() {
        final DesktopDisplayTarget target = binding(12, 7);
        final DesktopDisplayTarget changed = target.withProfile("display:wired:another");
        assertEquals(12, changed.workspaceDisplayId);
        assertEquals(7, changed.output.displayId);
        assertTrue(target.sameBinding(changed));
        assertEquals("display:wired:monitor", target.output.profileKey);
        assertFalse(target.sameBinding(binding(13, 7)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void representingARouteDoesNotEnableOutputSwitching() {
        binding(12, 7).requireSupportedBinding();
    }
}
