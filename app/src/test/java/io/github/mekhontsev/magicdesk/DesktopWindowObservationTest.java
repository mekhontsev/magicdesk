package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.junit.After;
import org.junit.Test;

import java.util.Collections;

public final class DesktopWindowObservationTest {
    private static final int DISPLAY_ID = 20;
    private static final int TASK_ID = 42;
    private static final String PACKAGE = "com.example.game";

    @After
    public void clearFailures() {
        DesktopProcessHealthRegistry.clearForTest();
    }

    @Test
    public void reportsRenderedApplicationAsReady() {
        final DesktopWindowObservation.TaskHealth health =
                DesktopWindowObservation.fromDump(normalDump(4321))
                        .health(task());

        assertTrue(health.rendered);
        assertTrue(health.inputFocused);
        assertEquals(Boolean.TRUE, health.processAlive);
        assertTrue(health.ready);
        assertFalse(health.blockedBySystemDialog);
        assertFalse(health.crashed);
    }

    @Test
    public void reportsCrashDialogInsteadOfReadyActivityRecord() {
        DesktopProcessHealthRegistry.record(
                DesktopProcessFailure.CRASH,
                PACKAGE,
                4321,
                TASK_ID,
                DISPLAY_ID,
                "NullPointerException");

        final DesktopWindowObservation.TaskHealth health =
                DesktopWindowObservation.fromDump(crashDump(4321))
                        .health(task());

        assertTrue(health.rendered);
        assertFalse(health.inputFocused);
        assertEquals(Boolean.FALSE, health.processAlive);
        assertFalse(health.ready);
        assertTrue(health.blockedBySystemDialog);
        assertTrue(health.crashed);
        assertEquals("crash_dialog", health.systemDialog.kind);
        assertTrue(DesktopWindowObservation.fromDump(crashDump(4321))
                .hasBlockingSystemDialog(
                        Integer.valueOf(DISPLAY_ID),
                        Integer.valueOf(TASK_ID),
                        "",
                        snapshot()));
    }

    @Test
    public void clearsFailureWhenSameTaskHasAReplacementProcess() {
        DesktopProcessHealthRegistry.record(
                DesktopProcessFailure.CRASH,
                PACKAGE,
                4321,
                TASK_ID,
                DISPLAY_ID,
                "old process");

        final DesktopWindowObservation.TaskHealth health =
                DesktopWindowObservation.fromDump(normalDump(5432))
                        .health(task());

        assertNull(health.failure);
        assertEquals(Boolean.TRUE, health.processAlive);
        assertTrue(health.ready);
        assertNull(DesktopProcessHealthRegistry.find(TASK_ID));
    }

    @Test
    public void treatsCrossPackagePermissionActivityAsBlockingDialog() {
        final String dump = normalDump(4321).replace(
                "displayId=20, name='app1 com.example.game/.MainActivity'",
                "displayId=20, name='permission "
                        + "com.android.permissioncontroller/.GrantPermissionsActivity'")
                + "";
        final String withPermissionWindow = dump.replace(
                "  FocusRequests:\n",
                "  FocusRequests:\n"
                        + "  Display: 20\n"
                        + "    Windows:\n"
                        + "      0: name=permission "
                        + "com.android.permissioncontroller/"
                        + ".GrantPermissionsActivity, id=9, displayId=20, "
                        + "inputConfig=0x0, alpha=1, "
                        + "applicationInfo.name=ActivityRecord{99 u0 "
                        + "com.android.permissioncontroller/"
                        + ".GrantPermissionsActivity t42}, ownerPid=777, "
                        + "ownerUid=1000, token=p\n");

        final DesktopWindowObservation.TaskHealth health =
                DesktopWindowObservation.fromDump(withPermissionWindow)
                        .health(task());

        assertTrue(health.blockedBySystemDialog);
        assertFalse(health.ready);
        assertFalse(health.crashed);
    }

    private static TaskRepository.TaskEntry task() {
        return new TaskRepository.TaskEntry(
                40,
                TASK_ID,
                DISPLAY_ID,
                PACKAGE,
                PACKAGE + "/.MainActivity",
                PACKAGE + "/.MainActivity",
                "freeform",
                new Rect(100, 100, 900, 700),
                false,
                true,
                true);
    }

    private static TaskRepository.Snapshot snapshot() {
        return new TaskRepository.Snapshot(
                Collections.singletonList(task()), true, "");
    }

    private static String normalDump(final int pid) {
        return "Input Dispatcher State:\n"
                + "  FocusedApplications:\n"
                + "    displayId=20, name='ActivityRecord{123 u0 "
                + PACKAGE + "/.MainActivity t42}', dispatchingTimeout=8000ms\n"
                + "  FocusedWindows:\n"
                + "    displayId=20, name='app1 "
                + PACKAGE + "/.MainActivity'\n"
                + "  FocusRequests:\n"
                + "  Display: 20\n"
                + "    Windows:\n"
                + "      0: name=app1 " + PACKAGE + "/.MainActivity, "
                + "id=2, displayId=20, inputConfig=0x0, alpha=1, "
                + "applicationInfo.name=ActivityRecord{123 u0 "
                + PACKAGE + "/.MainActivity t42}, "
                + "applicationInfo.token=x, ownerPid=" + pid + ", "
                + "ownerUid=10123, token=y\n";
    }

    private static String crashDump(final int pid) {
        return normalDump(pid).replace(
                "displayId=20, name='app1 " + PACKAGE + "/.MainActivity'",
                "displayId=0, name='touchpad "
                        + "io.github.mekhontsev.magicdesk/"
                        + ".MagicDeskTouchpadActivity'")
                + "      1: name=error1 Application Error: " + PACKAGE
                + ", id=1, displayId=20, inputConfig=0x0, alpha=1, "
                + "applicationInfo.name=, ownerPid=1000, ownerUid=1000, "
                + "token=z\n";
    }
}
