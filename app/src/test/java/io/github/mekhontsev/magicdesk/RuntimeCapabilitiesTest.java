package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RuntimeCapabilitiesTest {
    @Test public void shellModeIsAnObservationNotAnotherServicePrerequisite() throws Exception {
        for (final var mode : FrameworkDesktopShellApi.Mode.values()) {
            final var caps = new RuntimeCapabilities(35, true, true, true, DesktopSetupStatus.State.READY,
                    mode, RuntimeLimits.DEFAULT);
            assertEquals(mode, caps.desktopShellMode());
            for (final var service : RuntimeCapabilities.Service.values()) assertEquals("", caps.missing(service));
            final var desktop = caps.toJson().getJSONObject("desktop");
            assertTrue(desktop.getBoolean("ready"));
            assertEquals(mode.name().toLowerCase(java.util.Locale.ROOT), desktop.getString("shellMode"));
        }
        for (int sdk : new int[]{34, 35}) {
            final var caps = new RuntimeCapabilities(sdk, false, true, true, DesktopSetupStatus.State.READY,
                    FrameworkDesktopShellApi.Mode.NATIVE, RuntimeLimits.DEFAULT);
            assertEquals(FrameworkDesktopShellApi.Mode.UNKNOWN, caps.desktopShellMode());
        }
        assertEquals(FrameworkDesktopShellApi.Mode.UNKNOWN,
                new RuntimeCapabilities(34, true, true, true, DesktopSetupStatus.State.READY,
                        FrameworkDesktopShellApi.Mode.NATIVE, RuntimeLimits.DEFAULT).desktopShellMode());
    }

    @Test public void limitsRestrictServicesWithoutInventingCapabilities() {
        for (int sdk : new int[]{34, 35, 36}) {
            for (var access : RuntimeLimits.Access.values()) {
                for (boolean termux : new boolean[]{false, true}) {
                    for (boolean desktop : new boolean[]{false, true}) {
                        final var limits = new RuntimeLimits.Values(access, termux, desktop);
                        final var caps = new RuntimeCapabilities(sdk, true, true, true,
                                DesktopSetupStatus.State.READY, FrameworkDesktopShellApi.Mode.NATIVE, limits);
                        final boolean privileged = access != RuntimeLimits.Access.APP_ONLY;
                        assertEquals("", caps.missing(RuntimeCapabilities.Service.AUTOMATION));
                        assertEquals("", caps.missing(RuntimeCapabilities.Service.BUILTIN_UI));
                        assertEquals("", caps.missing(RuntimeCapabilities.Service.DISPLAYS));
                        assertEquals(privileged ? "" : "privileged_disabled", caps.missing(RuntimeCapabilities.Service.SHELL));
                        assertEquals(privileged ? "" : "privileged_disabled", caps.missing(RuntimeCapabilities.Service.VIRTUAL_DISPLAY));
                        assertEquals(termux ? "" : "termux_disabled", caps.missing(RuntimeCapabilities.Service.TERMUX));
                        assertEquals(privileged || termux ? "" : "graphics_executor", caps.missing(RuntimeCapabilities.Service.GRAPHICS));
                        assertEquals(privileged || termux ? "" : "terminal_backend", caps.missing(RuntimeCapabilities.Service.TERMINAL));
                        assertEquals(sdk < 35 ? "android_15" : !desktop ? "desktop_disabled"
                                : !privileged ? "privileged_disabled" : "", caps.missing(RuntimeCapabilities.Service.DESKTOP));
                        final var absent = new RuntimeCapabilities(sdk, false, false, false,
                                DesktopSetupStatus.State.READY, FrameworkDesktopShellApi.Mode.NATIVE, limits);
                        assertFalse(absent.missing(RuntimeCapabilities.Service.SHELL).isEmpty());
                        assertFalse(absent.missing(RuntimeCapabilities.Service.TERMUX).isEmpty());
                        assertFalse(absent.missing(RuntimeCapabilities.Service.TERMINAL).isEmpty());
                        assertFalse(absent.missing(RuntimeCapabilities.Service.DESKTOP).isEmpty());
                    }
                }
            }
        }
    }

    @Test public void desktopFrameworkFloorDoesNotDependOnProvisioning() {
        assertFalse(RuntimeCapabilities.supportsDesktop(34));
        assertTrue(RuntimeCapabilities.supportsDesktop(35));
        assertTrue(RuntimeCapabilities.supportsDesktop(36));
    }

    @Test public void servicesAreAvailableBeforeDesktopStartup() {
        final var caps = new RuntimeCapabilities(35, true, true, true, DesktopSetupStatus.State.READY, FrameworkDesktopShellApi.Mode.NATIVE, RuntimeLimits.DEFAULT);
        for (final var service : RuntimeCapabilities.Service.values()) {
            assertEquals(service.name(), "", caps.missing(service));
            assertEquals(service.name(), 0, caps.unavailableMessage(service));
        }
    }

    @Test public void shellFailureDoesNotGateTermuxOrUi() {
        final var caps = new RuntimeCapabilities(35, false, true, true, DesktopSetupStatus.State.READY, FrameworkDesktopShellApi.Mode.NATIVE, RuntimeLimits.DEFAULT);
        assertEquals("", caps.missing(RuntimeCapabilities.Service.TERMUX));
        assertEquals("", caps.missing(RuntimeCapabilities.Service.BUILTIN_UI));
        assertEquals("", caps.missing(RuntimeCapabilities.Service.AUTOMATION));
        assertEquals("", caps.missing(RuntimeCapabilities.Service.DISPLAYS));
        assertEquals("privileged_service", caps.missing(RuntimeCapabilities.Service.SHELL));
    }

    @Test public void requirementsAreNotClientPermissions() {
        assertEquals("android_15", new RuntimeCapabilities(34, true, true, true, DesktopSetupStatus.State.READY, FrameworkDesktopShellApi.Mode.NATIVE, RuntimeLimits.DEFAULT)
                .missing(RuntimeCapabilities.Service.DESKTOP));
        assertEquals("termux", new RuntimeCapabilities(35, true, false, false, DesktopSetupStatus.State.READY, FrameworkDesktopShellApi.Mode.NATIVE, RuntimeLimits.DEFAULT)
                .missing(RuntimeCapabilities.Service.TERMUX));
        assertEquals("termux_run_command", new RuntimeCapabilities(35, true, true, false, DesktopSetupStatus.State.READY, FrameworkDesktopShellApi.Mode.NATIVE, RuntimeLimits.DEFAULT)
                .missing(RuntimeCapabilities.Service.TERMUX));
    }

    @Test public void missingTermuxDoesNotGateIndependentServicesOrDesktop() {
        final var caps = new RuntimeCapabilities(35, true, false, false, DesktopSetupStatus.State.READY, FrameworkDesktopShellApi.Mode.NATIVE, RuntimeLimits.DEFAULT);
        for (final var service : RuntimeCapabilities.Service.values()) {
            assertEquals(service.name(), service == RuntimeCapabilities.Service.TERMUX ? "termux" : "",
                    caps.missing(service));
        }
    }

    @Test public void independentToolMatrixAcrossSupportedReleases() {
        for (int sdk : new int[] {34, 35, 36}) {
            for (boolean shell : new boolean[] {false, true}) {
                for (boolean installed : new boolean[] {false, true}) {
                    for (boolean authorized : new boolean[] {false, true}) {
                        final var caps = new RuntimeCapabilities(sdk, shell, installed, authorized, DesktopSetupStatus.State.READY, FrameworkDesktopShellApi.Mode.NATIVE, RuntimeLimits.DEFAULT);
                        assertEquals("", caps.missing(RuntimeCapabilities.Service.AUTOMATION));
                        assertEquals("", caps.missing(RuntimeCapabilities.Service.BUILTIN_UI));
                        assertEquals(shell ? "" : "privileged_service", caps.missing(RuntimeCapabilities.Service.SHELL));
                        assertEquals(shell ? "" : "privileged_service", caps.missing(RuntimeCapabilities.Service.VIRTUAL_DISPLAY));
                        assertEquals(shell || installed && authorized ? "" : "terminal_backend",
                                caps.missing(RuntimeCapabilities.Service.TERMINAL));
                        assertEquals(shell || installed && authorized ? "" : "graphics_executor",
                                caps.missing(RuntimeCapabilities.Service.GRAPHICS));
                        assertEquals(sdk < 35 ? "android_15" : !shell ? "privileged_service" : "",
                                caps.missing(RuntimeCapabilities.Service.DESKTOP));
                    }
                }
            }
        }
    }

    @Test public void desktopSetupStatesDoNotGateOtherServices() {
        for (final var setup : DesktopSetupStatus.State.values()) {
            final var caps = new RuntimeCapabilities(35, true, true, true, setup, FrameworkDesktopShellApi.Mode.NATIVE, RuntimeLimits.DEFAULT);
            final String expected = switch (setup) {
                case READY -> "";
                case CHECKING -> "desktop_setup_checking";
                case UNKNOWN -> "desktop_setup_unknown";
                case SETUP_REQUIRED -> "desktop_setup";
                case RESTART_REQUIRED -> "device_restart";
            };
            for (final var service : RuntimeCapabilities.Service.values()) {
                assertEquals(service.name(), service == RuntimeCapabilities.Service.DESKTOP ? expected : "",
                        caps.missing(service));
            }
            assertEquals("android_15", new RuntimeCapabilities(34, true, true, true, setup, FrameworkDesktopShellApi.Mode.NATIVE, RuntimeLimits.DEFAULT)
                    .missing(RuntimeCapabilities.Service.DESKTOP));
            assertEquals("privileged_service", new RuntimeCapabilities(35, false, true, true, setup, FrameworkDesktopShellApi.Mode.NATIVE, RuntimeLimits.DEFAULT)
                    .missing(RuntimeCapabilities.Service.DESKTOP));
        }
    }
}
