package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopSessionTransitionCoordinatorTest {
    @Test
    public void visiblePhonePanelIsReused() {
        assertFalse(DesktopSessionTransitionCoordinator.shouldOpenPhonePanel(
                true, true));
    }

    @Test
    public void missingPhonePanelIsRestoredWhenRequested() {
        assertTrue(DesktopSessionTransitionCoordinator.shouldOpenPhonePanel(
                true, false));
    }

    @Test
    public void phonePanelIsNotOpenedDuringFullExit() {
        assertFalse(DesktopSessionTransitionCoordinator.shouldOpenPhonePanel(
                false, false));
    }
}
