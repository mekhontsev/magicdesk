package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.Display;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class RuntimeDesktopInputCoordinatorTest {
    @Test
    public void pointerRoutingRequiresShellExternalDisplayAndPointer() {
        assertTrue(DesktopInputRelaySession.shouldRunRouting(
                true, 7, true));
        assertFalse(DesktopInputRelaySession.shouldRunRouting(
                false, 7, true));
        assertFalse(DesktopInputRelaySession.shouldRunRouting(
                true, Display.DEFAULT_DISPLAY, true));
        assertFalse(DesktopInputRelaySession.shouldRunRouting(
                true, 7, false));
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
    public void pointerBridgeRequiresShellAndExternalDesktop() {
        assertTrue(DesktopInputRelaySession.shouldRunPointerBridge(
                true, 7, Display.INVALID_DISPLAY));
        assertFalse(DesktopInputRelaySession.shouldRunPointerBridge(
                false, 7, Display.INVALID_DISPLAY));
        assertFalse(DesktopInputRelaySession.shouldRunPointerBridge(
                true, Display.DEFAULT_DISPLAY,
                Display.INVALID_DISPLAY));
        assertFalse(DesktopInputRelaySession.shouldRunPointerBridge(
                true, 7, 7));
        assertTrue(DesktopInputRelaySession.shouldRunPointerBridge(
                true, 8, 7));
    }

    @Test
    public void virtualPointerCanRouteWithoutPhysicalMice() {
        final DesktopMouseDevice physical = new DesktopMouseDevice(
                "/dev/input/event1", "usb-mouse", 1, 2);
        final DesktopMouseDevice virtual = new DesktopMouseDevice(
                "/dev/input/event2", "magicdesk-mouse", 0x4d44, 1);

        final List<DesktopMouseDevice> selected =
                DesktopInputRoutingSession.selectRoutedMice(
                        Arrays.asList(physical, virtual), false, true);

        assertEquals(1, selected.size());
        assertEquals("magicdesk-mouse", selected.get(0).location);
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
