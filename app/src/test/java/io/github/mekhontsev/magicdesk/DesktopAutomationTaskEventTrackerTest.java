package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import android.graphics.Rect;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Collections;

public final class DesktopAutomationTaskEventTrackerTest {
    @Test
    public void reportsTopActivityChangesFromExistingSnapshots()
            throws Exception {
        final DesktopAutomationTaskEventTracker tracker =
                new DesktopAutomationTaskEventTracker();
        tracker.observe(snapshot("com.example/.MainActivity"));
        final long afterId = DesktopAutomationEventJournal.latestId();

        tracker.observe(snapshot(
                "com.android.permissioncontroller/.GrantPermissionsActivity"));

        final JSONArray events = DesktopAutomationEventJournal.snapshot(
                afterId, 10);
        assertEquals(1, events.length());
        final JSONObject event = events.getJSONObject(0);
        assertEquals("top_activity_changed", event.getString("operation"));
        assertEquals(
                "com.android.permissioncontroller/.GrantPermissionsActivity",
                event.getJSONObject("data").getString("topActivity"));
    }

    private static TaskRepository.Snapshot snapshot(
            final String topActivity) {
        return new TaskRepository.Snapshot(
                Collections.singletonList(new TaskRepository.TaskEntry(
                        40,
                        42,
                        20,
                        "com.example",
                        "com.example/.MainActivity",
                        topActivity,
                        "freeform",
                        new Rect(100, 100, 900, 700),
                        false,
                        true,
                        true)),
                true,
                "");
    }
}
