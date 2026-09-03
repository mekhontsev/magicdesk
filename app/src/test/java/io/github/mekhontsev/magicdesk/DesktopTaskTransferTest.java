package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DesktopTaskTransferTest {
    @Test
    public void routeUsesDisplayRootForEveryConfiguredDesktop() {
        assertEquals(
                DesktopTaskTransfer.Route.DIRECT_ROOT,
                DesktopTaskTransfer.routeFor(
                        true, 3));
        assertEquals(
                DesktopTaskTransfer.Route.DIRECT_ROOT,
                DesktopTaskTransfer.routeFor(
                        false, 0));
        assertEquals(
                DesktopTaskTransfer.Route.UNAVAILABLE,
                DesktopTaskTransfer.routeFor(
                        false, 3));
    }
}
