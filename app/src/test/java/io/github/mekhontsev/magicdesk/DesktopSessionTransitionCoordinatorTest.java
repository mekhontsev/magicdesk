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
        assertTrue(plan(DesktopCloseMode.HOME).returnsTasks());
        assertFalse(DesktopSessionTransitionCoordinator.shouldOpenPhonePanel(
                DesktopCloseMode.HOME, false));
        assertFalse(DesktopSessionTransitionCoordinator.shouldOpenPhonePanel(
                DesktopCloseMode.HOME, true));
    }

    @Test
    public void closeToControlsAlsoParksTasksWhenPanelIsAlreadyVisible() {
        assertTrue(plan(DesktopCloseMode.CONTROL_PANEL).returnsTasks());
        assertFalse(DesktopSessionTransitionCoordinator.shouldOpenPhonePanel(
                DesktopCloseMode.CONTROL_PANEL, true));
    }

    @Test
    public void exitDoesNotRecaptureTasksReturnedByItsPreviousStep() {
        assertFalse(plan(DesktopCloseMode.EXIT).returnsTasks());
    }

    private static DesktopSessionEndPlan plan(final DesktopCloseMode mode) {
        return DesktopSessionEndPlan.create(DesktopWorkspaceSnapshot.empty(),
                DesktopDisplayTarget.wired(7), mode, true);
    }
}
