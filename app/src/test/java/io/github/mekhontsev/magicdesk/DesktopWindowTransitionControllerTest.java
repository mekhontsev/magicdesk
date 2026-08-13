package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopWindowTransitionControllerTest {
    private static final int FULLSCREEN = 1;
    private static final int FREEFORM = 5;

    @Test
    public void forgetsFullscreenStateOnlyAfterNativeRestore() {
        assertTrue(DesktopWindowTransitionController
                .shouldForgetManagedFullscreenState(
                        FULLSCREEN, FREEFORM, false));

        assertFalse(DesktopWindowTransitionController
                .shouldForgetManagedFullscreenState(
                        FULLSCREEN, FREEFORM, true));
        assertFalse(DesktopWindowTransitionController
                .shouldForgetManagedFullscreenState(
                        FREEFORM, FULLSCREEN, false));
    }
}
