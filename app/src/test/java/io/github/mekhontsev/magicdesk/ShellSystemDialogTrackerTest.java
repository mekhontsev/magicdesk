package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import android.os.Process;
import android.view.Display;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public final class ShellSystemDialogTrackerTest {
    @Test
    public void emitsOnlyConfiguredDisplayChanges() {
        final List<String> events = new ArrayList<>();
        final ShellSystemDialogTracker tracker =
                new ShellSystemDialogTracker(
                        new ShellSystemDialogPolicy(Set.of("android")),
                        (displayId, visible) ->
                                events.add(displayId + ":" + visible));

        tracker.configure(2, snapshot(2, "example.app", 10600));
        tracker.onInputWindowsChanged(snapshot(
                3, "android", Process.SYSTEM_UID));
        tracker.onInputWindowsChanged(snapshot(
                2, "android", Process.SYSTEM_UID));
        tracker.onInputWindowsChanged(snapshot(
                2, "android", Process.SYSTEM_UID));
        tracker.onInputWindowsChanged(snapshot(2, "example.app", 10600));

        assertEquals(java.util.Arrays.asList(
                "2:false", "2:true", "2:false"), events);
    }

    @Test
    public void clearingConfigurationReleasesVisibleHold() {
        final List<String> events = new ArrayList<>();
        final ShellSystemDialogTracker tracker =
                new ShellSystemDialogTracker(
                        new ShellSystemDialogPolicy(Set.of("android")),
                        (displayId, visible) ->
                                events.add(displayId + ":" + visible));

        tracker.configure(2, snapshot(2, "android", Process.SYSTEM_UID));
        tracker.configure(
                Display.INVALID_DISPLAY,
                FrameworkInputWindowState.Snapshot.unavailable());

        assertEquals(java.util.Arrays.asList("2:true", "2:false"), events);
    }

    private static FrameworkInputWindowState.Snapshot snapshot(
            final int displayId,
            final String packageName,
            final int ownerUid) {
        return FrameworkInputWindowState.fromWindows(Collections.singletonList(
                new FrameworkInputWindowState.Window(
                        displayId, packageName, packageName, ownerUid, 0)));
    }
}
