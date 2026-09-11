package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import static io.github.mekhontsev.magicdesk.DesktopHomeSurfaceRouter.Surface.*;

import java.util.List;
import org.junit.Test;

public final class DesktopHomeSurfacePolicyTest {
    @Test public void noWorkspaceRequiresNoMagicDeskHome() {
        final var selection = DesktopHomeSurfaceRouter.forWorkspaces(List.of());
        assertEquals(SYSTEM, selection.primary);
        assertEquals(SYSTEM, selection.surfaceOn(7));
        assertFalse(selection.secondaryDesktop);
    }

    @Test public void secondaryWorkspaceUsesLauncherOnDefaultDisplay() {
        final var selection = DesktopHomeSurfaceRouter.forWorkspaces(
                List.of(DesktopDisplayTarget.wired(7)));
        assertEquals(PHONE, selection.primary);
        assertEquals(DESKTOP, selection.surfaceOn(7));
        assertEquals(SYSTEM, selection.surfaceOn(8));
        assertTrue(selection.secondaryDesktop);
    }

    @Test public void defaultWorkspaceDoesNotEnableSecondaryComponents() {
        final var selection = DesktopHomeSurfaceRouter.forWorkspaces(
                List.of(DesktopDisplayTarget.phone()));
        assertEquals(DESKTOP, selection.primary);
        assertFalse(selection.secondaryDesktop);
    }

    @Test public void defaultAndSecondaryDesktopsAreIndependentOfOrderAndOutput() {
        final var phone = DesktopDisplayTarget.phone();
        final var external = DesktopDisplayTarget.wired(7);
        for (final var targets : List.of(List.of(phone, external), List.of(external, phone))) {
            final var selection = DesktopHomeSurfaceRouter.forWorkspaces(targets);
            assertEquals(DESKTOP, selection.primary);
            assertEquals(DESKTOP, selection.surfaceOn(7));
            assertTrue(selection.secondaryDesktop);
        }
        final var routed = DesktopDisplayTarget.restore(DesktopDisplayOutput.Kind.WIRED,
                12, 7, "", DesktopDisplayOutput.ActivationSource.ADOPTED_EXISTING);
        final var selection = DesktopHomeSurfaceRouter.forWorkspaces(List.of(routed));
        assertEquals(DESKTOP, selection.surfaceOn(12));
        assertEquals(SYSTEM, selection.surfaceOn(7));
    }

    @Test public void severalBuiltInPanelsUseTheSameResidencyPolicy() {
        final var selection = DesktopHomeSurfaceRouter.forWorkspaces(List.of(
                DesktopDisplayTarget.builtIn(0), DesktopDisplayTarget.builtIn(1),
                DesktopDisplayTarget.builtIn(2)));
        for (int display = 0; display <= 2; display++) {
            assertEquals(DESKTOP, selection.surfaceOn(display));
        }
        assertTrue(selection.secondaryDesktop);
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateResidencyCannotAdmitTwoHosts() {
        DesktopHomeSurfaceRouter.forWorkspaces(List.of(
                DesktopDisplayTarget.wired(7), DesktopDisplayTarget.wired(7)));
    }
}
