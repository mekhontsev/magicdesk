package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.Test;

public final class DesktopTaskRuntimeRegistryTest {
    @Test
    public void oneTaskStateOwnsBoundsAndWindowTransitionState() {
        final DesktopTaskRuntimeRegistry registry =
                new DesktopTaskRuntimeRegistry();
        final DesktopTaskRuntimeState state = registry.state(42);
        final Rect original = rect(10, 20, 300, 400);

        state.setLastWindowBounds(original);
        state.setFullscreenRestoreBounds(original);
        state.setAppRequestedFullscreen(true);
        assertTrue(state.beginFullscreenTransition());

        original.left = 0;
        original.top = 0;
        original.right = 0;
        original.bottom = 0;
        assertRect(10, 20, 300, 400, state.lastWindowBounds());
        assertRect(10, 20, 300, 400,
                state.fullscreenRestoreBounds());
        assertTrue(state.isAppRequestedFullscreen());
        assertTrue(state.isFullscreenTransition());
        assertFalse(state.beginFullscreenTransition());
    }

    @Test
    public void clearingNativeBoundsPreservesFullscreenState() {
        final DesktopTaskRuntimeRegistry registry =
                new DesktopTaskRuntimeRegistry();
        final DesktopTaskRuntimeState state = registry.state(42);
        state.setLastWindowBounds(rect(1, 2, 3, 4));
        state.setMaximizeRestoreBounds(rect(5, 6, 7, 8));
        state.beginBoundsTransition(rect(9, 10, 11, 12), true);
        state.setFullscreenRestoreBounds(rect(13, 14, 15, 16));
        state.setManualImmersiveOverride(true);

        registry.clearNativeBoundsState();

        assertNull(state.lastWindowBounds());
        assertNull(state.maximizeRestoreBounds());
        assertNull(state.boundsTransition());
        assertRect(13, 14, 15, 16,
                state.fullscreenRestoreBounds());
        assertTrue(state.hasManualImmersiveOverride());
    }

    @Test
    public void forgettingTaskInvalidatesLateCallbacks() {
        final DesktopTaskRuntimeRegistry registry =
                new DesktopTaskRuntimeRegistry();
        final DesktopTaskRuntimeState stale = registry.state(42);

        registry.forget(42);
        final DesktopTaskRuntimeState replacement = registry.state(42);

        assertFalse(registry.isCurrent(42, stale));
        assertTrue(registry.isCurrent(42, replacement));
        assertNotSame(stale, replacement);
    }

    @Test
    public void immersiveStartupStateIsConsumedAtomically() {
        final DesktopTaskRuntimeState state =
                new DesktopTaskRuntimeState(42);
        state.setStartupWindowed(true);

        assertTrue(state.consumeStartupWindowed());
        assertFalse(state.consumeStartupWindowed());
        assertNull(state.updateImmersiveRequested(false));
        assertEquals(Boolean.FALSE,
                state.updateImmersiveRequested(true));
        assertTrue(state.isImmersiveRequested());
    }

    private static void assertRect(
            final int left,
            final int top,
            final int right,
            final int bottom,
            final Rect actual) {
        assertEquals(left, actual.left);
        assertEquals(top, actual.top);
        assertEquals(right, actual.right);
        assertEquals(bottom, actual.bottom);
    }

    private static Rect rect(
            final int left,
            final int top,
            final int right,
            final int bottom) {
        final Rect bounds = new Rect();
        bounds.left = left;
        bounds.top = top;
        bounds.right = right;
        bounds.bottom = bottom;
        return bounds;
    }
}
