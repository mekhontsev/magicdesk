package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.Display;

import org.junit.Test;

public final class RuntimeDesktopInputCoordinatorTest {
    @Test
    public void relayRoutingRequiresShellExternalDisplayAndPointer() {
        assertTrue(DesktopInputRelaySession.shouldRunRouting(
                true, 7, DesktopInputRelayPolicy.KEYBOARD_AND_MOUSE, true));
        assertFalse(DesktopInputRelaySession.shouldRunRouting(
                false, 7, DesktopInputRelayPolicy.KEYBOARD_AND_MOUSE, true));
        assertFalse(DesktopInputRelaySession.shouldRunRouting(
                true, Display.DEFAULT_DISPLAY,
                DesktopInputRelayPolicy.KEYBOARD_AND_MOUSE, true));
        assertFalse(DesktopInputRelaySession.shouldRunRouting(
                true, 7, DesktopInputRelayPolicy.NONE, true));
        assertFalse(DesktopInputRelaySession.shouldRunRouting(
                true, 7, DesktopInputRelayPolicy.KEYBOARD_AND_MOUSE, false));
        assertTrue(DesktopInputRelaySession.shouldRunRouting(
                true, 7,
                new DesktopInputRelayPolicy(true, false), true));
    }

    @Test
    public void passiveKeyboardWatcherDoesNotCompeteWithRelaySession() {
        assertFalse(RuntimeDesktopInputCoordinator.shouldRunKeyboardWatcher(
                false, true, false));
        assertFalse(RuntimeDesktopInputCoordinator.shouldRunKeyboardWatcher(
                true, false, false));
        assertTrue(RuntimeDesktopInputCoordinator.shouldRunKeyboardWatcher(
                true, true, false));
        assertFalse(RuntimeDesktopInputCoordinator.shouldRunKeyboardWatcher(
                true, true, true));
    }

    @Test
    public void mouseBridgeRequiresShellBridgeAndExternalDesktop() {
        assertTrue(DesktopInputRelaySession.shouldRunMouseBridge(
                true, 7, true, Display.INVALID_DISPLAY));
        assertFalse(DesktopInputRelaySession.shouldRunMouseBridge(
                false, 7, true, Display.INVALID_DISPLAY));
        assertFalse(DesktopInputRelaySession.shouldRunMouseBridge(
                true, 7, false, Display.INVALID_DISPLAY));
        assertFalse(DesktopInputRelaySession.shouldRunMouseBridge(
                true, Display.DEFAULT_DISPLAY, true,
                Display.INVALID_DISPLAY));
        assertFalse(DesktopInputRelaySession.shouldRunMouseBridge(
                true, 7, true, 7));
        assertTrue(DesktopInputRelaySession.shouldRunMouseBridge(
                true, 8, true, 7));
    }

    @Test
    public void pointerViewportRecoversWhenExternalOwnershipEnds() {
        assertTrue(RuntimeDesktopInputCoordinator.shouldRecoverPointerViewport(
                7, Display.INVALID_DISPLAY, true));
        assertTrue(RuntimeDesktopInputCoordinator.shouldRecoverPointerViewport(
                7, Display.DEFAULT_DISPLAY, true));
        assertFalse(RuntimeDesktopInputCoordinator.shouldRecoverPointerViewport(
                7, Display.INVALID_DISPLAY, false));
        assertFalse(RuntimeDesktopInputCoordinator.shouldRecoverPointerViewport(
                Display.DEFAULT_DISPLAY, Display.INVALID_DISPLAY, true));
        assertFalse(RuntimeDesktopInputCoordinator.shouldRecoverPointerViewport(
                7, 8, true));
    }
}
