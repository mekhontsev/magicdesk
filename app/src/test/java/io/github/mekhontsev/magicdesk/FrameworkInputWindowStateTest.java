package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.os.Process;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public final class FrameworkInputWindowStateTest {
    @Test
    public void topFocusableSystemWindowIsTransientLayer() {
        final FrameworkInputWindowState.Snapshot snapshot =
                FrameworkInputWindowState.fromWindows(Arrays.asList(
                        window(2, "taskbar", 10647, 4),
                        window(2, "android", Process.SYSTEM_UID, 0),
                        window(2, "example.app", 10600, 0)));

        assertTrue(snapshot.available);
        assertEquals("android", snapshot.focusedWindow(2).packageName);
    }

    @Test
    public void focusedApplicationIsNotSystemLayer() {
        final FrameworkInputWindowState.Snapshot snapshot =
                FrameworkInputWindowState.fromWindows(Collections.singletonList(
                        window(2, "example.app", 10600, 0)));

        assertEquals("example.app", snapshot.focusedWindow(2).packageName);
    }

    @Test
    public void invisibleAndClonedWindowsCannotOwnFocus() {
        final FrameworkInputWindowState.Snapshot snapshot =
                FrameworkInputWindowState.fromWindows(Arrays.asList(
                        window(2, "android", Process.SYSTEM_UID, 2),
                        window(2, "android", Process.SYSTEM_UID, 65_536),
                        window(2, "example.app", 10600, 0)));

        assertEquals("example.app", snapshot.focusedWindow(2).packageName);
    }

    @Test
    public void unavailableSnapshotDoesNotReportSystemLayer() {
        final FrameworkInputWindowState.Snapshot snapshot =
                FrameworkInputWindowState.fromWindows(null);

        assertFalse(snapshot.available);
        assertNull(snapshot.focusedWindow(2));
    }

    private static FrameworkInputWindowState.Window window(
            final int displayId,
            final String packageName,
            final int ownerUid,
            final int inputConfig) {
        return new FrameworkInputWindowState.Window(
                displayId, packageName, packageName, ownerUid, inputConfig);
    }
}
