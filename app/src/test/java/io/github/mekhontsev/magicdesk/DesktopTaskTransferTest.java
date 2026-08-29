package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class DesktopTaskTransferTest {
    @Test
    public void routeFollowsApplicationWorkspaceOwnership() {
        assertEquals(
                DesktopTaskTransfer.Route.MANAGED_SESSION,
                DesktopTaskTransfer.routeFor(
                        DesktopTaskAreaPolicy.SESSION, 0));
        assertEquals(
                DesktopTaskTransfer.Route.DIRECT_ROOT,
                DesktopTaskTransfer.routeFor(
                        DesktopTaskAreaPolicy.INDEPENDENT, 3));
        assertEquals(
                DesktopTaskTransfer.Route.DIRECT_ROOT,
                DesktopTaskTransfer.routeFor(
                        DesktopTaskAreaPolicy.UNCONFIGURED, 0));
        assertEquals(
                DesktopTaskTransfer.Route.UNAVAILABLE,
                DesktopTaskTransfer.routeFor(
                        DesktopTaskAreaPolicy.UNCONFIGURED, 3));
    }
}
