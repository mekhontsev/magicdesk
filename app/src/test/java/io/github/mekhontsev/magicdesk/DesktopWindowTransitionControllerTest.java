package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

public final class DesktopWindowTransitionControllerTest {
    private static final int FULLSCREEN = 1;
    private static final int FREEFORM = 5;

    @Test
    public void forgetsFullscreenStateOnlyAfterNativeRestore() {
        assertTrue(DesktopWindowTransitionController
                .shouldForgetManagedFullscreenState(
                        FULLSCREEN, FREEFORM, false, false));

        assertFalse(DesktopWindowTransitionController
                .shouldForgetManagedFullscreenState(
                        FULLSCREEN, FREEFORM, true, false));
        assertFalse(DesktopWindowTransitionController
                .shouldForgetManagedFullscreenState(
                        FULLSCREEN, FREEFORM, false, true));
        assertFalse(DesktopWindowTransitionController
                .shouldForgetManagedFullscreenState(
                        FREEFORM, FULLSCREEN, false, false));
    }

    @Test
    public void semanticRequestDoesNotExposeItsBoundsInstance() {
        final Rect source = new Rect(10, 20, 800, 600);
        final DesktopWindowTransitionRequest request =
                DesktopWindowTransitionRequest.restoreFreeform(
                        3,
                        42,
                        source,
                        DesktopTaskDensity.INHERIT,
                        "test");

        final Rect first = request.bounds();
        final Rect second = request.bounds();

        assertNotSame(source, first);
        assertNotSame(first, second);
        assertEquals(
                DesktopWindowTransitionRequest.Operation.RESTORE_FREEFORM,
                request.operation);
    }

    @Test
    public void semanticRequestValidatesRequiredGeometry() {
        assertThrows(
                IllegalArgumentException.class,
                () -> DesktopWindowTransitionRequest.restoreFreeform(
                        3,
                        42,
                        null,
                        DesktopTaskDensity.INHERIT,
                        "test"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DesktopWindowTransitionRequest.enterFullscreen(
                        -1,
                        42,
                        DesktopTaskDensity.INHERIT,
                        "test"));
        assertThrows(
                IllegalArgumentException.class,
                () -> DesktopWindowTransitionRequest.enterFullscreen(
                        3,
                        42,
                        DesktopTaskDensity.UNCHANGED,
                        "test"));
    }

    @Test
    public void restoreCanTargetAFullscreenTaskDirectly() {
        assertTrue(DesktopWindowTransitionController.supportsFullscreenTask(
                DesktopWindowTransitionController.SHORTCUT_RESTORE));
    }

    @Test
    public void restoreShortcutPreservesThreeDistinctStages() {
        assertEquals(
                DesktopWindowTransitionController.RestoreShortcutAction
                        .RESTORE_FULLSCREEN,
                DesktopWindowTransitionController.classifyRestoreShortcut(
                        true, true));
        assertEquals(
                DesktopWindowTransitionController.RestoreShortcutAction
                        .RESTORE_WINDOW_BOUNDS,
                DesktopWindowTransitionController.classifyRestoreShortcut(
                        false, true));
        assertEquals(
                DesktopWindowTransitionController.RestoreShortcutAction
                        .DEMOTE,
                DesktopWindowTransitionController.classifyRestoreShortcut(
                        false, false));
    }
}
