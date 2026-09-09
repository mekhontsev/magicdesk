package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RuntimeCapabilitiesTest {
    @Test public void desktopFrameworkFloorDoesNotDependOnProvisioning() {
        assertFalse(RuntimeCapabilities.supportsDesktop(34));
        assertTrue(RuntimeCapabilities.supportsDesktop(35));
        assertTrue(RuntimeCapabilities.supportsDesktop(36));
    }

    @Test public void desktopProvisioningDoesNotGateIndependentServices() {
        final var caps = new RuntimeCapabilities(35, true, true, true, false);
        for (final var service : RuntimeCapabilities.Service.values()) {
            assertEquals(service.name(), service == RuntimeCapabilities.Service.DESKTOP ? "desktop_setup" : "",
                    caps.missing(service));
        }
    }

    @Test public void shellFailureDoesNotGateTermuxOrUi() {
        final var caps = new RuntimeCapabilities(35, false, true, true, true);
        assertEquals("", caps.missing(RuntimeCapabilities.Service.TERMUX));
        assertEquals("", caps.missing(RuntimeCapabilities.Service.BUILTIN_UI));
        assertEquals("", caps.missing(RuntimeCapabilities.Service.AUTOMATION));
        assertEquals("shizuku", caps.missing(RuntimeCapabilities.Service.SHELL));
    }

    @Test public void requirementsAreNotClientPermissions() {
        assertEquals("android_15", new RuntimeCapabilities(34, true, true, true, true)
                .missing(RuntimeCapabilities.Service.DESKTOP));
        assertEquals("termux", new RuntimeCapabilities(35, true, false, false, true)
                .missing(RuntimeCapabilities.Service.TERMUX));
        assertEquals("termux_run_command", new RuntimeCapabilities(35, true, true, false, true)
                .missing(RuntimeCapabilities.Service.TERMUX));
    }
}
