package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PhoneControlPanelControllerTest {
    @Test
    public void closeDesktopVisibilityFollowsHomeSession() {
        assertTrue(PhoneControlPanelController.shouldShowCloseDesktop(true));
        assertFalse(PhoneControlPanelController.shouldShowCloseDesktop(false));
    }

    @Test
    public void closeDesktopRequiresShellOnlyForExecution() {
        assertTrue(PhoneControlPanelController.canCloseDesktop(true, true));
        assertFalse(PhoneControlPanelController.canCloseDesktop(true, false));
        assertFalse(PhoneControlPanelController.canCloseDesktop(false, true));
    }
}
