package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopSessionTransitionCoordinatorTest {
    @Test
    public void adoptedWiredDisplayRemainsSystemOwned() {
        assertFalse(DesktopSessionTransitionCoordinator
                .shouldReturnTransportToMirror(
                        DesktopDisplayTarget.wired(7), true));
    }

    @Test
    public void requestedWiredDisplayReturnsToMirror() {
        assertTrue(DesktopSessionTransitionCoordinator
                .shouldReturnTransportToMirror(
                        DesktopDisplayTarget.wired(7).withActivationSource(
                                DesktopDisplayTarget.ActivationSource
                                        .MAGICDESK_REQUESTED),
                        true));
    }

    @Test
    public void managedWirelessDisplayReturnsToMirror() {
        assertTrue(DesktopSessionTransitionCoordinator
                .shouldReturnTransportToMirror(
                        DesktopDisplayTarget.wireless(8), true));
    }

    @Test
    public void unmanagedTransportNeverReturnsToMirror() {
        assertFalse(DesktopSessionTransitionCoordinator
                .shouldReturnTransportToMirror(
                        DesktopDisplayTarget.wired(7).withActivationSource(
                                DesktopDisplayTarget.ActivationSource
                                        .MAGICDESK_REQUESTED),
                        false));
    }

    @Test
    public void adoptedDisplayReusesVisiblePhonePanel() {
        assertFalse(DesktopSessionTransitionCoordinator.shouldOpenPhonePanel(
                true, false, true));
        assertTrue(DesktopSessionTransitionCoordinator.shouldOpenPhonePanel(
                true, false, false));
    }

    @Test
    public void managedDisplayRestoresPanelAfterModeTransition() {
        assertTrue(DesktopSessionTransitionCoordinator.shouldOpenPhonePanel(
                true, true, true));
    }
}
