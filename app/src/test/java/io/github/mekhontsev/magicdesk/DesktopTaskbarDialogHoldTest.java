package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopTaskbarDialogHoldTest {
    @Test
    public void visibleTaskbarRemainsVisibleUntilFreshSnapshot() {
        final DesktopTaskbarDialogHold hold =
                new DesktopTaskbarDialogHold();

        assertTrue(hold.setDialogVisible(true, true));
        assertTrue(hold.currentVisibility(false));
        assertTrue(hold.applySnapshot(false));
        assertTrue(hold.setDialogVisible(false, true));
        assertTrue(hold.currentVisibility(false));
        assertFalse(hold.applySnapshot(false));
    }

    @Test
    public void hiddenTaskbarDoesNotAppearForDialog() {
        final DesktopTaskbarDialogHold hold =
                new DesktopTaskbarDialogHold();

        assertTrue(hold.setDialogVisible(true, false));
        assertFalse(hold.currentVisibility(true));
        assertFalse(hold.applySnapshot(true));
    }

    @Test
    public void repeatedEventsDoNotReplaceHeldVisibility() {
        final DesktopTaskbarDialogHold hold =
                new DesktopTaskbarDialogHold();

        assertTrue(hold.setDialogVisible(true, true));
        assertFalse(hold.setDialogVisible(true, false));
        assertTrue(hold.currentVisibility(false));
    }
}
