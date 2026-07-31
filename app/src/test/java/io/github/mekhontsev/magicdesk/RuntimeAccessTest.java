package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.EnumSet;

import org.junit.After;
import org.junit.Test;

public final class RuntimeAccessTest {
    @After
    public void resetRuntimeAccess() {
        RuntimeAccess.configure(
                new SessionProfile(
                        SessionProfile.PrivilegeMode.AUTO,
                        SessionProfile.DisplayTarget.AUTO),
                RuntimeAccess.Backend.BASIC);
    }

    @Test
    public void basicCanOnlyLaunchPublicApplications() {
        assertEquals(
                EnumSet.of(RuntimeAccess.Capability.PUBLIC_APP_LAUNCH),
                RuntimeAccess.capabilitiesFor(RuntimeAccess.Backend.BASIC));
    }

    @Test
    public void shellAndRootShizukuUseTheSameBoundedCapabilities() {
        final EnumSet<RuntimeAccess.Capability> expected = EnumSet.of(
                RuntimeAccess.Capability.PUBLIC_APP_LAUNCH,
                RuntimeAccess.Capability.EXACT_TASKS,
                RuntimeAccess.Capability.TASK_CONTROL,
                RuntimeAccess.Capability.GLOBAL_INPUT,
                RuntimeAccess.Capability.KEYBOARD_LAYOUT_CONTROL,
                RuntimeAccess.Capability.KEYBOARD_LAYOUT_SHORTCUT,
                RuntimeAccess.Capability.RIGHT_CLICK_REMAP,
                RuntimeAccess.Capability.CONSOLE_CONTROL,
                RuntimeAccess.Capability.DISPLAY_OVERRIDES,
                RuntimeAccess.Capability.EXTERNAL_CAPTION_VISIBILITY,
                RuntimeAccess.Capability.PHONE_SCREEN_CONTROL,
                RuntimeAccess.Capability.SCREENSHOT,
                RuntimeAccess.Capability.CHARGE_SEPARATION);

        assertEquals(
                expected,
                RuntimeAccess.capabilitiesFor(
                        RuntimeAccess.Backend.SHIZUKU_SHELL));
        assertEquals(
                expected,
                RuntimeAccess.capabilitiesFor(
                        RuntimeAccess.Backend.SHIZUKU_ROOT));
        assertTrue(expected.contains(
                RuntimeAccess.Capability.GLOBAL_INPUT));
        assertTrue(expected.contains(
                RuntimeAccess.Capability.PHONE_SCREEN_CONTROL));
        assertTrue(expected.contains(
                RuntimeAccess.Capability.EXTERNAL_CAPTION_VISIBILITY));
        assertFalse(expected.contains(
                RuntimeAccess.Capability.PHONE_SCREEN_WAKE_GUARD));
        assertFalse(expected.contains(
                RuntimeAccess.Capability.KERNEL_FIXES));
    }

    @Test
    public void rootHasEveryCapability() {
        assertEquals(
                EnumSet.allOf(RuntimeAccess.Capability.class),
                RuntimeAccess.capabilitiesFor(RuntimeAccess.Backend.ROOT));
    }

    @Test
    public void onlyRootBackendPassesRootCommandGate() throws Exception {
        for (final RuntimeAccess.Backend backend
                : RuntimeAccess.Backend.values()) {
            RuntimeAccess.configure(null, backend);
            if (backend == RuntimeAccess.Backend.ROOT) {
                RuntimeAccess.requireRootCommands("test");
                assertTrue(RuntimeAccess.allowsRootCommands());
            } else {
                assertFalse(RuntimeAccess.allowsRootCommands());
                try {
                    RuntimeAccess.requireRootCommands("test");
                    fail("root command gate accepted " + backend);
                } catch (IOException expected) {
                    assertTrue(expected.getMessage().contains(
                            RuntimeAccess.backendName()));
                }
            }
        }
    }

    @Test
    public void onlyShizukuBackendsPassShizukuCommandGate() {
        for (final RuntimeAccess.Backend backend
                : RuntimeAccess.Backend.values()) {
            RuntimeAccess.configure(null, backend);
            final boolean expected =
                    backend == RuntimeAccess.Backend.SHIZUKU_SHELL
                            || backend == RuntimeAccess.Backend.SHIZUKU_ROOT;
            assertEquals(expected, RuntimeAccess.allowsShizukuCommands());
        }
    }
}
