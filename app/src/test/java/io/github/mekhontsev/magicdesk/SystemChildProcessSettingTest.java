package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class SystemChildProcessSettingTest {
    @Test public void checkboxInvertsMonitoringAndReadsBackBothDirections() throws Exception {
        final FakeAccess access = new FakeAccess();
        assertTrue(SystemChildProcessSetting.setDisabled(access, true));
        assertFalse(access.monitoring);
        assertTrue(SystemChildProcessSetting.setDisabled(access, false));
        assertTrue(access.monitoring);
        assertEquals(2, access.writes);
        assertEquals(4, access.reads);
    }

    @Test public void unchangedValueDoesNotWrite() throws Exception {
        final FakeAccess access = new FakeAccess();
        assertFalse(SystemChildProcessSetting.setDisabled(access, false));
        access.monitoring = false;
        assertFalse(SystemChildProcessSetting.setDisabled(access, true));
        assertEquals(0, access.writes);
    }

    @Test public void unknownReadNeverBecomesDisabled() {
        final FakeAccess access = new FakeAccess();
        access.failRead = true;
        assertThrows(IOException.class, () -> SystemChildProcessSetting.setDisabled(access, true));
        assertEquals(0, access.writes);
    }

    @Test public void permissionFailureAndIgnoredWritesAreNotSuccess() {
        final FakeAccess access = new FakeAccess();
        access.failWrite = true;
        assertThrows(IOException.class, () -> SystemChildProcessSetting.setDisabled(access, true));
        access.failWrite = false;
        access.ignoreWrite = true;
        assertThrows(IOException.class, () -> SystemChildProcessSetting.setDisabled(access, true));
        assertTrue(access.monitoring);
    }

    @Test public void failedReadBackIsNotSuccess() {
        final FakeAccess access = new FakeAccess();
        access.failReadBack = true;
        assertThrows(IOException.class, () -> SystemChildProcessSetting.setDisabled(access, true));
        assertEquals(1, access.writes);
    }

    @Test public void stateUsesAuthorizedServiceWithoutDesktopOrPreferenceCopy() throws Exception {
        final String source = Files.readString(Path.of(RuntimeSourceFixture.MAIN + "SystemChildProcessSetting.java"));
        assertTrue(source.contains("ShellAccess.readChildProcessMonitoringDefault()"));
        assertTrue(source.contains("Settings.Global.getString(context.getContentResolver()"));
        assertTrue(source.contains("ShellAccess.run(writeCommand(enabled))"));
        assertFalse(source.contains("DesktopRuntime"));
        assertFalse(source.contains("SharedPreferences"));
        assertFalse(source.contains("DeviceSetupManager"));
        final String service = RuntimeSourceFixture.methods("ShellCommandService", "getChildProcessMonitoringDefault");
        assertFalse(service.contains("mContext"));
        assertTrue(service.contains("Binder.clearCallingIdentity()"));
        assertTrue(RuntimeSourceFixture.finallyBlock("ShellCommandService", "getChildProcessMonitoringDefault")
                .contains("Binder.restoreCallingIdentity(identity)"));
    }

    @Test public void globalOverridePrecedesFrameworkPropertyAndUsesBooleanStrings() {
        assertTrue(SystemChildProcessSetting.monitoringEnabled(null, true));
        assertFalse(SystemChildProcessSetting.monitoringEnabled(null, false));
        assertTrue(SystemChildProcessSetting.monitoringEnabled("", true));
        assertFalse(SystemChildProcessSetting.monitoringEnabled("false", true));
        assertTrue(SystemChildProcessSetting.monitoringEnabled("true", false));
        assertFalse(SystemChildProcessSetting.monitoringEnabled("0", true));
        assertFalse(SystemChildProcessSetting.monitoringEnabled("1", true));
        assertEquals("/system/bin/settings put global settings_enable_monitor_phantom_procs false",
                SystemChildProcessSetting.writeCommand(false));
        assertEquals("/system/bin/settings put global settings_enable_monitor_phantom_procs true",
                SystemChildProcessSetting.writeCommand(true));
    }

    @Test public void renderingUsesOnlyShellReadinessAndKeepsUnknownExplicit() throws Exception {
        RuntimeSourceFixture.verify("""
            static class R { static class string {
                static final int settings_system_desktop_mode_shell=1, settings_system_desktop_mode_saving=2,
                    settings_child_process_description=3, settings_child_process_unavailable=4;
            }}
            static class ShellAccess { static boolean ready; static boolean isReady() { return ready; } }
            static class SystemChildProcessSetting {
                static boolean fail, disabled; static int reads;
                static boolean readDisabled(Object context) throws IOException {
                    reads++; if(fail) throw new IOException(); return disabled;
                }
            }
            static class View {
                Boolean value; boolean editable; int status;
                void renderChildProcessSetting(Boolean v, boolean e, int s) { value=v; editable=e; status=s; }
            }
            View mView = new View(); boolean mSystemSettingBusy;
            boolean isFinishing() { return false; } boolean isDestroyed() { return false; }
            """ + RuntimeSourceFixture.methods("SettingsActivity", "renderChildProcessSetting") + """
            public static void verify() {
                Fixture f = new Fixture(); f.renderChildProcessSetting();
                check(f.mView.value==null && !f.mView.editable && f.mView.status==1, "shell absent");
                check(SystemChildProcessSetting.reads==0, "unavailable service called");
                ShellAccess.ready=true; SystemChildProcessSetting.disabled=true;
                f.renderChildProcessSetting();
                check(f.mView.value && f.mView.editable && f.mView.status==3, "shared setting unavailable");
                f.mSystemSettingBusy=true; f.renderChildProcessSetting();
                check(!f.mView.editable && f.mView.status==2, "concurrent write allowed");
                SystemChildProcessSetting.fail=true; f.renderChildProcessSetting();
                check(f.mView.value==null && !f.mView.editable && f.mView.status==4, "unknown became false");
            }
            """);
    }

    private static final class FakeAccess implements SystemChildProcessSetting.Access {
        boolean monitoring = true;
        boolean failRead, failReadBack, failWrite, ignoreWrite;
        int reads, writes;
        @Override public boolean monitoringEnabled() throws IOException {
            reads++;
            if (failRead || (failReadBack && writes > 0)) throw new IOException("read unavailable");
            return monitoring;
        }
        @Override public void setMonitoringEnabled(boolean enabled) throws IOException {
            writes++;
            if (failWrite) throw new IOException("permission denied");
            if (!ignoreWrite) monitoring = enabled;
        }
    }
}
