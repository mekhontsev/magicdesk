package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AppWindowStateTest {
    private static final RelativeWindowBounds BOUNDS =
            new RelativeWindowBounds(5000, 5000, 6000, 7000);

    @Test
    public void savedBoundsRestoreAnUnspecifiedModeAsWindowed() {
        assertTrue(new AppWindowState(null, BOUNDS).shouldLaunchWindowed());
    }

    @Test
    public void explicitFullscreenWinsOverSavedBounds() {
        assertFalse(new AppWindowState(
                AppWindowState.Mode.FULLSCREEN,
                BOUNDS).shouldLaunchWindowed());
    }

    @Test
    public void explicitWindowedDoesNotRequireSavedBounds() {
        assertTrue(new AppWindowState(
                AppWindowState.Mode.WINDOWED,
                null).shouldLaunchWindowed());
    }
}
