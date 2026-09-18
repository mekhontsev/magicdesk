package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class IntegrationStatusDialogsTest {
    @Test public void disabledIntegrationRetainsActualPackageAndPermissionState() throws Exception {
        final var endpoint = new TermuxIntegration.Endpoint("org.example.termux", false, true,
                null, "/example/home", 12345, true, "Permission is not granted");
        assertEquals(R.string.control_status_disabled, IntegrationStatusDialogs.termuxStatus(endpoint));
        org.junit.Assert.assertTrue(endpoint.installed);
        org.junit.Assert.assertTrue(endpoint.permissionRequired);
        org.junit.Assert.assertFalse(endpoint.canRequestPermission());
        org.junit.Assert.assertFalse(endpoint.available());
        assertEquals("Permission is not granted", endpoint.capabilityError);
        assertEquals("Termux integration is disabled in Limits", endpoint.error);
        org.junit.Assert.assertThrows(IllegalStateException.class, endpoint::requireAvailable);
        org.junit.Assert.assertFalse(endpoint.toJson().getBoolean("enabled"));
        org.junit.Assert.assertTrue(endpoint.toJson().getBoolean("installed"));
    }

    @Test public void missingPackageIsNotInstalled() {
        assertEquals(R.string.control_termux_not_installed,
                IntegrationStatusDialogs.termuxStatus(endpoint(false, false, "Package is missing")));
    }

    @Test public void missingPermissionAndIncompatibleServiceRequireSetup() {
        assertEquals(R.string.control_termux_setup_required,
                IntegrationStatusDialogs.termuxStatus(endpoint(true, true, "Permission is not granted")));
        assertEquals(R.string.control_termux_setup_required,
                IntegrationStatusDialogs.termuxStatus(endpoint(true, false, "Service is not exported")));
    }

    @Test public void availableCommandEndpointIsReady() {
        assertEquals(R.string.control_status_ready,
                IntegrationStatusDialogs.termuxStatus(endpoint(true, false, "")));
    }

    @Test public void desktopSummaryExplainsSetupAndRestartSeparately() {
        assertEquals(R.string.control_status_ready, desktopStatus(DesktopSetupStatus.State.READY));
        assertEquals(R.string.control_desktop_checking, desktopStatus(DesktopSetupStatus.State.CHECKING));
        assertEquals(R.string.control_desktop_setup, desktopStatus(DesktopSetupStatus.State.SETUP_REQUIRED));
        assertEquals(R.string.control_desktop_restart, desktopStatus(DesktopSetupStatus.State.RESTART_REQUIRED));
        assertEquals(R.string.control_desktop_unavailable, desktopStatus(DesktopSetupStatus.State.UNKNOWN));
        assertEquals(R.string.control_desktop_unavailable, IntegrationStatusDialogs.desktopStatus(
                new RuntimeCapabilities(34, true, true, true, DesktopSetupStatus.State.READY, RuntimeLimits.DEFAULT)));
        assertEquals(R.string.control_desktop_unavailable, IntegrationStatusDialogs.desktopStatus(
                new RuntimeCapabilities(35, false, true, true, DesktopSetupStatus.State.READY, RuntimeLimits.DEFAULT)));
    }

    private static int desktopStatus(DesktopSetupStatus.State state) {
        return IntegrationStatusDialogs.desktopStatus(new RuntimeCapabilities(35, true, true, true, state, RuntimeLimits.DEFAULT));
    }

    private static TermuxIntegration.Endpoint endpoint(boolean installed, boolean permissionRequired, String error) {
        return new TermuxIntegration.Endpoint("org.example.termux", true, installed, null, "", -1,
                permissionRequired, error);
    }
}
