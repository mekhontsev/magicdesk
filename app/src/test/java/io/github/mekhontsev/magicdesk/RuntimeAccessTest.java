package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.EnumSet;

import org.junit.After;
import org.junit.Test;

public final class RuntimeAccessTest {
    @After
    public void resetRuntimeAccess() {
        RuntimeAccess.configure(
                new SessionProfile(SessionProfile.DisplayTarget.AUTO),
                RuntimeAccess.Backend.UNAVAILABLE);
    }

    @Test
    public void unavailableBackendHasNoCapabilities() {
        assertEquals(
                EnumSet.noneOf(RuntimeAccess.Capability.class),
                RuntimeAccess.capabilitiesFor(
                        RuntimeAccess.Backend.UNAVAILABLE));
    }

    @Test
    public void shizukuHasTheBoundedRuntimeCapabilities() {
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
                RuntimeAccess.Capability.DEVICE_LOCK,
                RuntimeAccess.Capability.SCREENSHOT,
                RuntimeAccess.Capability.SYSTEM_WALLPAPER_READ,
                RuntimeAccess.Capability.CHARGE_SEPARATION,
                RuntimeAccess.Capability.HARDWARE_MONITORING,
                RuntimeAccess.Capability.HARDWARE_VENDOR_CONTROL);

        assertEquals(
                expected,
                RuntimeAccess.capabilitiesFor(
                        RuntimeAccess.Backend.SHIZUKU));
        assertTrue(expected.contains(
                RuntimeAccess.Capability.GLOBAL_INPUT));
        assertTrue(expected.contains(
                RuntimeAccess.Capability.PHONE_SCREEN_CONTROL));
        assertTrue(expected.contains(
                RuntimeAccess.Capability.EXTERNAL_CAPTION_VISIBILITY));
        assertTrue(expected.contains(
                RuntimeAccess.Capability.DEVICE_LOCK));
        assertTrue(expected.contains(
                RuntimeAccess.Capability.SYSTEM_WALLPAPER_READ));
        assertTrue(expected.contains(
                RuntimeAccess.Capability.HARDWARE_MONITORING));
        assertTrue(expected.contains(
                RuntimeAccess.Capability.HARDWARE_VENDOR_CONTROL));
    }

    @Test
    public void onlyShizukuBackendsPassShizukuCommandGate() {
        for (final RuntimeAccess.Backend backend
                : RuntimeAccess.Backend.values()) {
            RuntimeAccess.configure(null, backend);
            final boolean expected = backend == RuntimeAccess.Backend.SHIZUKU;
            assertEquals(expected, RuntimeAccess.allowsShizukuCommands());
        }
    }
}
