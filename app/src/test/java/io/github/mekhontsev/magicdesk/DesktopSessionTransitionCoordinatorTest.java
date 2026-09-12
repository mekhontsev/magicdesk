package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopSessionTransitionCoordinatorTest {
    @Test
    public void visiblePhonePanelIsReused() {
        assertFalse(DesktopSessionTransitionCoordinator.shouldOpenPhonePanel(
                DesktopCloseMode.CONTROL_PANEL, true));
    }

    @Test
    public void missingPhonePanelIsRestoredWhenRequested() {
        assertTrue(DesktopSessionTransitionCoordinator.shouldOpenPhonePanel(
                DesktopCloseMode.CONTROL_PANEL, false));
    }

    @Test
    public void phonePanelIsNotOpenedDuringFullExit() {
        assertFalse(DesktopSessionTransitionCoordinator.shouldOpenPhonePanel(
                DesktopCloseMode.EXIT, false));
    }

    @Test
    public void closeFromHomeOrOverviewParksTasksWithoutOpeningControls() {
        org.junit.Assert.assertEquals(DesktopSessionEndPlan.Tasks.RETURN_TO_DEFAULT_AND_REMEMBER, plan(DesktopCloseMode.HOME).tasks);
        assertFalse(DesktopSessionTransitionCoordinator.shouldOpenPhonePanel(
                DesktopCloseMode.HOME, false));
        assertFalse(DesktopSessionTransitionCoordinator.shouldOpenPhonePanel(
                DesktopCloseMode.HOME, true));
    }

    @Test
    public void closeToControlsAlsoParksTasksWhenPanelIsAlreadyVisible() {
        org.junit.Assert.assertEquals(DesktopSessionEndPlan.Tasks.RETURN_TO_DEFAULT_AND_REMEMBER, plan(DesktopCloseMode.CONTROL_PANEL).tasks);
        assertFalse(DesktopSessionTransitionCoordinator.shouldOpenPhonePanel(
                DesktopCloseMode.CONTROL_PANEL, true));
    }

    @Test
    public void exitReturnsTasksWithoutRetainingThem() {
        org.junit.Assert.assertEquals(DesktopSessionEndPlan.Tasks.RETURN_TO_DEFAULT, plan(DesktopCloseMode.EXIT).tasks);
    }

    private static DesktopSessionEndPlan plan(final DesktopCloseMode mode) {
        return DesktopSessionEndPlan.create(DesktopWorkspaceSnapshot.empty(),
                DesktopDisplayTarget.wired(7), mode, true);
    }
}
