package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public final class SystemDesktopModeSettingTest {
    @Test
    public void enablesAndDisablesActualAndroidValue() throws Exception {
        final FakeAccess access = new FakeAccess();
        assertTrue(SystemDesktopModeSetting.setEnabled(access, true));
        assertTrue(access.enabled);
        assertTrue(SystemDesktopModeSetting.setEnabled(access, false));
        assertFalse(access.enabled);
        assertEquals(2, access.writes);
    }

    @Test
    public void unchangedValueDoesNotWriteOrRequireShell() throws Exception {
        final FakeAccess access = new FakeAccess();
        access.allowed = false;
        assertFalse(SystemDesktopModeSetting.setEnabled(access, false));
        assertEquals(0, access.writes);
    }

    @Test
    public void rejectsChangeWhenSessionOrShellStateDoesNotAllowIt() {
        final FakeAccess access = new FakeAccess();
        access.allowed = false;
        assertThrows(IOException.class, () -> SystemDesktopModeSetting.setEnabled(access, true));
        assertEquals(0, access.writes);
    }

    @Test
    public void unavailableReadMustNotBecomeDisabled() {
        final FakeAccess access = new FakeAccess();
        access.readFails = true;
        assertThrows(IOException.class, () -> SystemDesktopModeSetting.setEnabled(access, true));
        assertEquals(0, access.writes);
    }

    @Test
    public void failedWriteIsNotReportedAsSaved() {
        final FakeAccess access = new FakeAccess();
        access.writeFails = true;
        assertThrows(IOException.class, () -> SystemDesktopModeSetting.setEnabled(access, true));
        assertFalse(access.enabled);
    }

    @Test
    public void verifiesAndroidRetainedRequestedValue() {
        final FakeAccess access = new FakeAccess();
        access.ignoreWrite = true;
        assertThrows(IOException.class, () -> SystemDesktopModeSetting.setEnabled(access, true));
        assertEquals(1, access.writes);
    }

    @Test
    public void commandsAreConfinedToTheOptionalGlobalSetting() {
        assertEquals("/system/bin/settings put global force_desktop_mode_on_external_displays 1",
                SystemDesktopModeSetting.writeCommand(true));
        assertEquals("/system/bin/settings put global force_desktop_mode_on_external_displays 0",
                SystemDesktopModeSetting.writeCommand(false));
        assertEquals("/system/bin/settings delete global force_desktop_mode_on_external_displays",
                SystemDesktopModeSetting.resetCommand());
    }

    @Test
    public void resetRemovesBothEnabledAndDisabledOverrides() throws Exception {
        final FakeAccess access = new FakeAccess();
        access.enabled = true;
        assertTrue(SystemDesktopModeSetting.reset(access));
        assertFalse(access.enabled);
        assertFalse(access.hasOverride);
        access.hasOverride = true;
        assertFalse(SystemDesktopModeSetting.reset(access));
        assertFalse(access.hasOverride);
        assertEquals(2, access.resets);
        assertEquals(0, access.writes);
    }

    @Test
    public void resetChecksSessionEvenWhenAndroidModeIsAlreadyDisabled() {
        final FakeAccess access = new FakeAccess();
        access.allowed = false;
        assertThrows(IOException.class, () -> SystemDesktopModeSetting.reset(access));
        assertEquals(0, access.resets);
    }

    @Test
    public void resetRequiresSuccessfulReadAndWrite() {
        final FakeAccess access = new FakeAccess();
        access.readFails = true;
        assertThrows(IOException.class, () -> SystemDesktopModeSetting.reset(access));
        assertEquals(0, access.resets);
        access.readFails = false;
        access.writeFails = true;
        assertThrows(IOException.class, () -> SystemDesktopModeSetting.reset(access));
        assertTrue(access.hasOverride);
    }

    @Test
    public void resetVerifiesAndroidBeforeClearingCompatibilityOverrides() {
        final FakeAccess access = new FakeAccess();
        access.enabled = true;
        access.ignoreWrite = true;
        assertThrows(IOException.class, () -> DesktopCompatibilitySettings.resetDefaults(access,
                () -> { throw new AssertionError("must not clear preferences after failed Android reset"); }));
        assertEquals(1, access.resets);
    }

    @Test
    public void combinedResetClearsOverridesAfterAndroidAndReportsStorageFailure() throws Exception {
        final FakeAccess access = new FakeAccess();
        access.enabled = true;
        assertTrue(DesktopCompatibilitySettings.resetDefaults(access, () -> {
            assertFalse(access.hasOverride);
            return true;
        }));
        final IOException error = assertThrows(IOException.class,
                () -> DesktopCompatibilitySettings.resetDefaults(access, () -> false));
        assertTrue(error.getMessage().contains("preferences could not be saved"));
    }

    @Test
    public void optionalSettingHasNoPreferenceCopyOrRequiredSetupGate() throws Exception {
        final String source = source("SystemDesktopModeSetting");
        assertTrue(source.contains("Settings.Global.getInt("));
        assertFalse(source.contains("SharedPreferences"));
        assertFalse(source.contains("DeviceSetupManager"));
        assertFalse(source.contains("reboot("));
        final String settings = source("SettingsActivity");
        assertTrue(settings.contains("SystemDesktopModeSetting.setEnabled(context, enabled)"));
        assertFalse(settings.contains("DeviceSetupManager.configure("));
        assertFalse(settings.contains("DeviceSetupManager.reboot("));
        final String diagnostics = source("CompatibilityDiagnostics");
        assertFalse(diagnostics.contains("WM-DESKTOP-001"));
        assertTrue(diagnostics.contains("SystemDesktopModeSetting.read(context)"));
    }

    private static String source(final String name) throws IOException {
        return Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/" + name + ".java"));
    }

    private static final class FakeAccess implements SystemDesktopModeSetting.Access {
        boolean enabled;
        boolean allowed = true;
        boolean readFails;
        boolean writeFails;
        boolean ignoreWrite;
        boolean hasOverride = true;
        int writes;
        int resets;

        @Override
        public boolean read() throws IOException {
            if (readFails) {
                throw new IOException("read failed");
            }
            return enabled;
        }

        @Override
        public boolean canChange() {
            return allowed;
        }

        @Override
        public void write(final boolean value) throws IOException {
            writes++;
            if (writeFails) {
                throw new IOException("write failed");
            }
            if (!ignoreWrite) {
                enabled = value;
                hasOverride = true;
            }
        }

        @Override
        public void reset() throws IOException {
            resets++;
            if (writeFails) {
                throw new IOException("reset failed");
            }
            if (!ignoreWrite) {
                enabled = false;
                hasOverride = false;
            }
        }
    }
}
