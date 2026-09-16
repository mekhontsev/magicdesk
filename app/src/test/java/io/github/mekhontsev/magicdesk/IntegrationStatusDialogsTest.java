package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import org.junit.Test;

public class IntegrationStatusDialogsTest {
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

    private static TermuxIntegration.Endpoint endpoint(boolean installed, boolean permissionRequired, String error) {
        return new TermuxIntegration.Endpoint("org.example.termux", installed, null, "", -1,
                permissionRequired, error);
    }
}
