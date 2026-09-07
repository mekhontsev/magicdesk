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
                DesktopInputRoutingSession.selectRelayMice(
                        Arrays.asList(physical, virtual));

        assertEquals(1, selected.size());
        assertEquals("magicdesk-mouse", selected.get(0).location);
    }

    @Test
    public void capturedKeyboardAndCompositePointerKeepTheirSystemRoute() {
        final String sharedPort = "bluetooth-controller-port";
        final DesktopKeyboardDevice physicalKeyboard = new DesktopKeyboardDevice(
                "/dev/input/event1", sharedPort, 1, 2);
        final DesktopMouseDevice physicalPointer = new DesktopMouseDevice(
                "/dev/input/event2", sharedPort, 1, 2);
        final DesktopKeyboardDevice first = new DesktopKeyboardDevice(
                "/dev/input/event3", "magicdesk-keyboard-0", 0x4d44, 0x4b00);
        final DesktopKeyboardDevice second = new DesktopKeyboardDevice(
                "/dev/input/event4", "magicdesk-keyboard-1", 0x4d44, 0x4b01);
        final DesktopMouseDevice mouse = new DesktopMouseDevice(
                "/dev/input/event5", "magicdesk-mouse", 0x4d44, 1);

        assertEquals(Arrays.asList(first, second),
                DesktopInputRoutingSession.selectRelayKeyboards(
                        Arrays.asList(physicalKeyboard, first, second)));
        assertEquals(Arrays.asList(mouse),
                DesktopInputRoutingSession.selectRelayMice(
                        Arrays.asList(physicalPointer, mouse)));
    }

    @Test
    public void missingVirtualDevicesCannotFallBackToPhysicalSources() {
        assertTrue(DesktopInputRoutingSession.selectRelayKeyboards(Arrays.asList(
                new DesktopKeyboardDevice("/dev/input/event1", "usb-keyboard", 1, 2)))
                .isEmpty());
        assertTrue(DesktopInputRoutingSession.selectRelayMice(Arrays.asList(
                new DesktopMouseDevice("/dev/input/event2", "usb-mouse", 1, 2)))
                .isEmpty());
    }
}
