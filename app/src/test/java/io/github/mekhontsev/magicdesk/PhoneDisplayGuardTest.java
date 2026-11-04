package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class PhoneDisplayGuardTest {
    @Test public void helperChecksRestoreBeforeOffAndRetainsFailedRestoration() throws Exception {
        RuntimeSourceFixture.verify("""
                static final String READY = "ready", RESTORED = "restored", ERROR = "error",
                        RESTORE = "restore", HEARTBEAT = "ping";
                static class DisplayPowerCommands { static final String POWER_OFF = "power-off"; }
                static class SystemClock { static long elapsedRealtime() { return 42; } }
                static final java.util.List<String> calls = new java.util.ArrayList<>();
                static int failAt;
                final java.util.concurrent.atomic.AtomicBoolean mDisplayOverrideActive =
                        new java.util.concurrent.atomic.AtomicBoolean();
                final java.util.concurrent.atomic.AtomicLong mLastHeartbeat =
                        new java.util.concurrent.atomic.AtomicLong();
                final String mRestoreOperation = "power-reset";
                static boolean requestDisplayPower(String operation) {
                    calls.add(operation); return calls.size() != failAt;
                }
                void startWatchdog() { calls.add("watchdog"); }
                public static void verify() throws Exception {
                    check(java.lang.reflect.Modifier.isSynchronized(Fixture.class
                            .getDeclaredMethod("restoreDisplay").getModifiers()), "restore acknowledgement races watchdog");
                    java.io.InputStream previous = System.in;
                    try {
                        calls.clear(); failAt = 1;
                        Fixture denied = new Fixture();
                        System.setIn(new java.io.ByteArrayInputStream(new byte[0]));
                        try { denied.run(); throw new AssertionError("accepted restore denial"); }
                        catch (java.io.IOException expected) { }
                        check(calls.equals(java.util.List.of("power-reset")), "powered off before restoration admission");
                        check(!denied.mDisplayOverrideActive.get(), "claimed denied override");

                        calls.clear(); failAt = 2;
                        Fixture offDenied = new Fixture();
                        try { offDenied.run(); throw new AssertionError("accepted power-off denial"); }
                        catch (java.io.IOException expected) { }
                        check(offDenied.restoreDisplay(), "cleanup after failed off");
                        check(calls.equals(java.util.List.of("power-reset", "power-off", "power-reset")), "partial failure not reset");

                        calls.clear(); failAt = 0;
                        Fixture normal = new Fixture();
                        System.setIn(new java.io.ByteArrayInputStream("ping\\nrestore\\n".getBytes()));
                        normal.run();
                        check(calls.equals(java.util.List.of("power-reset", "power-off", "watchdog", "power-reset")), "normal protocol");
                        check(!normal.mDisplayOverrideActive.get() && normal.mLastHeartbeat.get() == 42, "normal state");
                        normal.restoreDisplay();
                        check(calls.size() == 4, "duplicate restore");

                        calls.clear(); failAt = 0;
                        Fixture lost = new Fixture();
                        System.setIn(new java.io.ByteArrayInputStream(new byte[0]));
                        lost.run();
                        failAt = 4;
                        check(!lost.restoreDisplay() && lost.mDisplayOverrideActive.get(), "lost restoration retry state");
                        failAt = 0;
                        check(lost.restoreDisplay() && !lost.mDisplayOverrideActive.get(), "restoration retry");
                    } finally { System.setIn(previous); }
                }
                """ + RuntimeSourceFixture.methods("PhoneDisplayGuardCommand", "run", "restoreDisplay")
                        .replace("android.os.SystemClock", "SystemClock")
                        .replace("BufferedReader", "java.io.BufferedReader")
                        .replace("InputStreamReader", "java.io.InputStreamReader")
                        .replace("StandardCharsets", "java.nio.charset.StandardCharsets")
                        .replace("IOException", "java.io.IOException"));
    }

    @Test public void unrelatedDesktopCloseCannotReleaseIndependentPanelOwner() throws Exception {
        RuntimeSourceFixture.verify("""
                static final Object LOCK = new Object(), OPERATION_LOCK = new Object();
                static class Session { int mDesktopDisplayId; Session(int id) { mDesktopDisplayId = id; } }
                static Session sSession;
                static int restores;
                static boolean disableLocked() { restores++; sSession = null; return true; }
                public static void verify() {
                    sSession = new Session(-1);
                    check(restoreForDisplay(8) && restores == 0, "Desktop closed a phone-only owner");
                    check(restoreForDisplay(0) && restores == 0, "phone Desktop closed an independent owner");
                    sSession = new Session(4);
                    check(restoreForDisplay(8) && restores == 0, "unrelated Desktop closed owner");
                    check(restoreForDisplay(4) && restores == 1 && sSession == null, "owning display not restored");
                    check(restoreForDisplay(4) && restores == 1, "duplicate restore");
                }
                """ + RuntimeSourceFixture.methods("PhoneDisplayGuard", "restoreForDisplay"));
    }

    @Test public void optionalFirmwareFailureDoesNotReleaseCpuWork() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Log { static void w(String tag, String message, Exception error) { } }
                static class ShellAccess { static String usefulMessage(Exception error) { return error.getMessage(); } }
                static class Lease {
                    String failure = "";
                    Object observation = new Object();
                    boolean cpuHeld = true, released;
                    void release(Object object) { check(object == observation, "wrong resource"); released = true; }
                }
                static class Protection { boolean released; void release(Lease owner) { released = true; } }
                final Protection mProtection = new Protection();
                public static void verify() {
                    Fixture fixture = new Fixture();
                    Lease lease = new Lease();
                    fixture.fail(lease, new Exception("vendor denied"));
                    check(lease.cpuHeld && lease.released && lease.observation == null, "CPU ownership lost with vendor hints");
                    check(lease.failure.equals("vendor denied") && fixture.mProtection.released, "failure not exposed/cleaned");
                }
                """ + RuntimeSourceFixture.methods("ShellBackgroundWork", "fail"));
        String acquire = RuntimeSourceFixture.methods("ShellBackgroundWork", "acquire");
        assertTrue(acquire.contains("displayId < 0"));
        assertTrue(acquire.contains("displayId == 0 && keepDisplayAwake"));
        assertFalse(acquire.contains("displayId <= 0"));
    }

    @Test public void shortcutsAndPowerStayOutsideDesktopAndVendorOwnership() throws Exception {
        String header = RuntimeSourceFixture.methods("PhoneControlPanelController", "createHeader");
        assertTrue(header.contains("terminals.setOnLongClickListener"));
        assertTrue(header.contains("mActions.openGraphics()"));
        assertTrue(header.contains("blackScreen.setOnLongClickListener"));
        assertTrue(header.contains("mActions.showScreenPowerOptions()"));
        String graphics = RuntimeSourceFixture.methods("ControlActivity", "openGraphics");
        assertTrue(graphics.contains("ToolApplications.open"));
        assertTrue(graphics.contains("ToolLaunchTarget.resolve(\"phone\""));
        String menu = RuntimeSourceFixture.methods("ControlActivity", "showScreenPowerOptions", "setPhoneScreenOff");
        assertTrue(menu.contains("PhoneDisplayGuard.checkAvailable"));
        assertTrue(menu.contains("PhoneDisplayGuard.setScreenOff"));
        assertFalse(menu.contains("DesktopOperations"));
        assertFalse(menu.contains("externalDesktopActive"));
        assertFalse(menu.contains("R.string.status_phone_screen_off"));
        assertTrue(RuntimeSourceFixture.methods("ControlActivity", "refresh")
                .contains("PhoneDisplayGuard.isScreenOff()"));
        String guard = Files.readString(Path.of(RuntimeSourceFixture.MAIN + "PhoneDisplayGuard.java"));
        assertFalse(guard.contains("platform.nubia"));
        assertFalse(guard.contains("desktopDisplayId <= 0"));
        assertTrue(guard.contains("Intent.ACTION_SCREEN_ON"));
        assertFalse(guard.contains("Intent.ACTION_SCREEN_OFF"));
        assertTrue(guard.contains("context.unregisterReceiver"));
        assertTrue(guard.contains("acknowledgedRestore || resetWithoutSession()"));
        assertTrue(guard.contains("ShellAccess.acquireBackgroundWork(0, 0, false"));
        assertTrue(guard.contains("setScreenOff(off, android.view.Display.INVALID_DISPLAY, result)"));
        assertTrue(RuntimeSourceFixture.methods("MagicDeskRuntimeService", "closeRuntime")
                .contains("PhoneDisplayGuard.requestRestore()"));
        assertFalse(RuntimeSourceFixture.methods("MagicDeskRuntimeService", "destroyDesktopRuntime")
                .contains("requestRestore"));
    }
}
