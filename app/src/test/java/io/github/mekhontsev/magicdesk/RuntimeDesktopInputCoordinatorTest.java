package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.Display;

import org.junit.Test;

public final class RuntimeDesktopInputCoordinatorTest {
    @Test
    public void routesInputOnlyToExternalDesktopWithBridgeSupport() {
        assertEquals(7, RuntimeDesktopInputCoordinator.routingDisplayId(
                7, true));
        assertEquals(Display.INVALID_DISPLAY,
                RuntimeDesktopInputCoordinator.routingDisplayId(7, false));
        assertEquals(Display.INVALID_DISPLAY,
                RuntimeDesktopInputCoordinator.routingDisplayId(
                        Display.DEFAULT_DISPLAY, true));
    }

    @Test
    public void keyboardWatcherRequiresShellAndKeyboardOrRouting() {
        assertFalse(RuntimeDesktopInputCoordinator.shouldRunKeyboardWatcher(
                false, true, Display.INVALID_DISPLAY));
        assertFalse(RuntimeDesktopInputCoordinator.shouldRunKeyboardWatcher(
                true, false, Display.INVALID_DISPLAY));
        assertTrue(RuntimeDesktopInputCoordinator.shouldRunKeyboardWatcher(
                true, true, Display.INVALID_DISPLAY));
        assertTrue(RuntimeDesktopInputCoordinator.shouldRunKeyboardWatcher(
                true, false, 7));
    }

    @Test
    public void mouseBridgeRequiresShellBridgeAndExternalDesktop() {
        assertTrue(RuntimeDesktopInputCoordinator.shouldRunMouseBridge(
                true, 7, true, Display.INVALID_DISPLAY));
        assertFalse(RuntimeDesktopInputCoordinator.shouldRunMouseBridge(
                false, 7, true, Display.INVALID_DISPLAY));
        assertFalse(RuntimeDesktopInputCoordinator.shouldRunMouseBridge(
                true, 7, false, Display.INVALID_DISPLAY));
        assertFalse(RuntimeDesktopInputCoordinator.shouldRunMouseBridge(
                true, Display.DEFAULT_DISPLAY, true,
                Display.INVALID_DISPLAY));
        assertFalse(RuntimeDesktopInputCoordinator.shouldRunMouseBridge(
                true, 7, true, 7));
        assertTrue(RuntimeDesktopInputCoordinator.shouldRunMouseBridge(
                true, 8, true, 7));
    }
}
