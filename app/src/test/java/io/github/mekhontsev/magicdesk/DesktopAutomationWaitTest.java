package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.graphics.Rect;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public final class DesktopAutomationWaitTest {
    @Test
    public void selectionIncludesPhoneAndHonorsExplicitDisplayScope() {
        final var desktop = task(41, 4, true);
        final var phone = task(10, 0, false);
        final var full = new TaskRepository.Snapshot(
                List.of(desktop), List.of(phone), true, "");
        assertEquals(List.of(desktop, phone),
                DesktopAutomationController.selectObservedTasks(full, 4, null).tasks);
        assertEquals(List.of(desktop),
                DesktopAutomationController.selectObservedTasks(full, 4, 4).tasks);
        assertEquals(List.of(phone),
                DesktopAutomationController.selectObservedTasks(full, 4, 0).tasks);
        assertEquals(List.of(desktop), full.tasks);
        assertEquals(List.of(phone), full.phoneTasks);
    }

    @Test
    public void unavailableInactiveAndUnobservedDisplaysStayUnknown() throws Exception {
        final var empty = new TaskRepository.Snapshot(List.of(), true, "");
        final var unavailable = new TaskRepository.Snapshot(List.of(), false, "shell lost");
        assertNull(DesktopAutomationController.selectObservedTasks(null, 4, 4));
        assertNull(DesktopAutomationController.selectObservedTasks(unavailable, 4, 4));
        assertNull(DesktopAutomationController.selectObservedTasks(empty, -1, 0));
        final var outsideScope = DesktopAutomationController.selectObservedTasks(empty, 4, 9);
        assertNull(outsideScope);
        assertFalse(observe("task_absent", outsideScope, 41, 9).getBoolean("matched"));
        assertFalse(observe("task_absent", unavailable, 41, 4).getBoolean("matched"));
        assertFalse(observe("task_present", null, 41, 4).getBoolean("matched"));
    }

    @Test
    public void absenceUsesTheSelectedScopeAndRequiresAnAvailableSnapshot() throws Exception {
        final var full = new TaskRepository.Snapshot(
                List.of(task(41, 4, true)), List.of(task(10, 0, false)), true, "");
        final var allObserved = DesktopAutomationController.selectObservedTasks(full, 4, null);
        assertFalse(observe("task_absent", allObserved, 41, null).getBoolean("matched"));
        assertTrue(observe("task_absent", new TaskRepository.Snapshot(List.of(), true, ""),
                99, null).getBoolean("matched"));
        final var desktop = DesktopAutomationController.selectObservedTasks(full, 4, 4);
        assertFalse(observe("task_absent", desktop, 41, 4).getBoolean("matched"));
        assertTrue(observe("task_absent", desktop, 10, 4).getBoolean("matched"));
        final var phone = DesktopAutomationController.selectObservedTasks(full, 4, 0);
        assertFalse(observe("task_absent", phone, 10, 0).getBoolean("matched"));
        assertTrue(observe("task_absent", phone, 41, 0).getBoolean("matched"));
    }

    @Test
    public void presenceAndFocusUsePublishedEntriesIncludingPhoneTasks() throws Exception {
        final var full = new TaskRepository.Snapshot(
                List.of(task(41, 4, true)), List.of(task(10, 0, false)), true, "");
        final var selected = DesktopAutomationController.selectObservedTasks(full, 4, null);
        assertTrue(observe("task_present", selected, 10, null).getBoolean("matched"));
        assertTrue(observe("task_focused", selected, 41, null).getBoolean("matched"));
        assertFalse(observe("task_focused", selected, 10, null).getBoolean("matched"));
    }

    @Test
    public void viewInputWindowAndFreshSnapshotConditionsRetainBoundedRechecks() {
        for (final String condition : List.of("ui_visible", "ui_element_state", "popup_state",
                "taskbar_visible", "wallpaper_rendered", "app_ready", "app_crashed",
                "app_not_responding", "system_dialog_visible")) {
            assertEquals(200L, DesktopAutomationController.waitInterval(condition, 1_000L, false));
            assertEquals(17L, DesktopAutomationController.waitInterval(condition, 17L, false));
        }
        for (final String condition : List.of("task_present", "task_absent", "task_focused",
                "task_windowing_mode", "task_bounds", "desktop_active",
                "desktop_inactive", "pointer_ready", "self_test_finished")) {
            assertEquals(1_000L, DesktopAutomationController.waitInterval(condition, 1_000L, false));
            assertEquals(200L, DesktopAutomationController.waitInterval(condition, 1_000L, true));
            assertEquals(17L, DesktopAutomationController.waitInterval(condition, 17L, true));
        }
    }

    @Test
    public void waitLoopUsesJournalWakeWithoutTaskRecheckPolling() throws Exception {
        RuntimeSourceFixture.verify("""
                static final long MAX_WAIT_MILLIS = 60000L, WAIT_RECHECK_MILLIS = 200L;
                static long now;
                static int observations;
                static List<Long> waits = new ArrayList<>();
                static class JSONException extends Exception {}
                static class JSONObject {
                    Map<String, Object> values = new HashMap<>();
                    JSONObject put(String key, Object value) throws JSONException {
                        values.put(key, value); return this;
                    }
                    long optLong(String key, long fallback) {
                        return ((Number) values.getOrDefault(key, fallback)).longValue();
                    }
                    boolean optBoolean(String key, boolean fallback) {
                        return (Boolean) values.getOrDefault(key, fallback);
                    }
                }
                static String requiredString(JSONObject args, String key) {
                    return (String) args.values.get(key);
                }
                static class SystemClock { static long uptimeMillis() { return now; } }
                static class DesktopAutomationEventJournal {
                    static long latestId() { return 0; }
                    static long awaitChange(long cursor, long timeout) throws InterruptedException {
                        waits.add(timeout);
                        now += Math.min(timeout, 400L - now);
                        return now == 400L ? 1 : cursor;
                    }
                }
                static class DesktopAutomationErrorCode {
                    static final int INVALID_ARGUMENT = 1, ACTION_FAILED = 2, TIMEOUT = 3;
                }
                static class DesktopAutomationResult {
                    final JSONObject data;
                    DesktopAutomationResult(JSONObject data) { this.data = data; }
                    static DesktopAutomationResult success(Object... args) {
                        return new DesktopAutomationResult((JSONObject) args[1]);
                    }
                    static DesktopAutomationResult failure(Object... args) {
                        throw new AssertionError(Arrays.toString(args));
                    }
                }
                DesktopAutomationResult record(String action, DesktopAutomationResult value) {
                    return value;
                }
                JSONObject observeCondition(String condition, JSONObject args) throws JSONException {
                    observations++;
                    return new JSONObject().put("matched", now == 400L)
                            .put("taskObservationRecheck", args.optBoolean("fresh", false));
                }
                public static void verify() throws Exception {
                    Fixture fixture = new Fixture();
                    fixture.waitFor(new JSONObject().put("condition", "task_present")
                            .put("timeoutMillis", 1000L));
                    check(waits.equals(List.of(1000L)), "task wait added a periodic recheck");
                    check(observations == 2, "task condition was observed without a journal event");
                    now = 0; observations = 0; waits.clear();
                    fixture.waitFor(new JSONObject().put("condition", "ui_visible")
                            .put("timeoutMillis", 1000L));
                    check(waits.equals(List.of(200L, 200L)), "View fallback was removed");
                    check(observations == 3, "View callback gap was not rechecked");
                    now = 0; observations = 0; waits.clear();
                    fixture.waitFor(new JSONObject().put("condition", "app_ready")
                            .put("timeoutMillis", 1000L));
                    check(waits.equals(List.of(200L, 200L)), "input-window fallback was removed");
                    check(observations == 3, "input window after task event was not observed");
                    now = 0; observations = 0; waits.clear();
                    fixture.waitFor(new JSONObject().put("condition", "task_absent")
                            .put("timeoutMillis", 1000L).put("fresh", true));
                    check(waits.equals(List.of(200L, 200L)), "global bounded observation was removed");
                    check(observations == 3, "fresh task state was not rechecked without callbacks");
                    now = 0; observations = 0; waits.clear();
                    DesktopAutomationResult expired = fixture.waitFor(new JSONObject()
                            .put("condition", "self_test_finished").put("timeoutMillis", 100L));
                    check(!expired.data.optBoolean("matched", true), "expired wait matched");
                    check(expired.data.optBoolean("waitExpired", false), "expiration not reported");
                    check(expired.data.optLong("timeoutMillis", 0) == 100L, "timeout missing");
                    check(waits.equals(List.of(100L)), "self-test wait added polling");
                    DesktopAutomationResult finished = fixture.waitFor(new JSONObject()
                            .put("condition", "self_test_finished").put("timeoutMillis", 1000L));
                    check(finished.data.optBoolean("matched", false), "later completion lost");
                    check(!finished.data.optBoolean("waitExpired", true), "matched wait expired");
                    check(now == 400L, "expiration changed the observed operation");
                }
                """ + RuntimeSourceFixture.methods("DesktopAutomationController",
                "waitFor", "waitInterval"));
    }

    @Test
    public void noSessionAndGlobalAbsenceUseAuthoritativeFreshSnapshots() throws Exception {
        RuntimeSourceFixture.verify("""
                static int activeDisplay = 4, queries;
                static TaskRepository.Snapshot published, global;
                static class Display { static final int DEFAULT_DISPLAY = 0; }
                static class JSONException extends Exception {}
                static class JSONObject {
                    Map<String, Object> values = new HashMap<>();
                    JSONObject put(String key, Object value) throws JSONException {
                        values.put(key, value); return this;
                    }
                    boolean has(String key) { return values.containsKey(key); }
                }
                static int requiredInt(JSONObject args, String key) {
                    return ((Number) args.values.get(key)).intValue();
                }
                static String requiredString(JSONObject args, String key) {
                    return (String) args.values.get(key);
                }
                static class TaskRepository {
                    record TaskEntry(int taskId, int displayId) {}
                    record Snapshot(List<TaskEntry> tasks, List<TaskEntry> phoneTasks,
                            boolean available, String error) {
                        Snapshot(List<TaskEntry> tasks, boolean available, String error) {
                            this(tasks, List.of(), available, error);
                        }
                    }
                    static Snapshot loadAllNow() { queries++; return global; }
                }
                static class DesktopRuntimeBridge {
                    static int getActiveDesktopDisplayId() { return activeDisplay; }
                }
                static class MagicDeskRuntime {
                    static TaskRepository.Snapshot observedTaskSnapshot(int display) {
                        return activeDisplay < 0 ? null : published;
                    }
                }
                static JSONObject args(int taskId, Integer displayId) throws Exception {
                    JSONObject args = new JSONObject().put("taskId", taskId);
                    if (displayId != null) args.put("displayId", displayId);
                    return args;
                }
                static TaskRepository.Snapshot observedWaitTasks(String condition,
                        JSONObject args, JSONObject observation) throws Exception {
                    return observedWaitTasks(args.put("condition", condition), observation);
                }
                public static void verify() throws Exception {
                    TaskRepository.TaskEntry desktop = new TaskRepository.TaskEntry(41, 4);
                    TaskRepository.TaskEntry phone = new TaskRepository.TaskEntry(10, 0);
                    TaskRepository.TaskEntry other = new TaskRepository.TaskEntry(90, 9);
                    published = new TaskRepository.Snapshot(List.of(desktop), List.of(phone), true, "");
                    global = new TaskRepository.Snapshot(List.of(desktop, phone, other), true, "");
                    JSONObject observation = new JSONObject();
                    TaskRepository.Snapshot result = observedWaitTasks("task_present", args(41, 4), observation);
                    check(findTask(result, 41) == desktop && queries == 0, "scoped wait did not reuse provider");
                    result = observedWaitTasks("task_present", args(10, null), observation);
                    check(findTask(result, 10) == phone && queries == 0, "known phone task caused global query");
                    result = observedWaitTasks("task_absent", args(90, null), observation);
                    check(findTask(result, 90) == other && queries == 1, "partial snapshot claimed global absence");
                    check("global".equals(observation.values.get("taskObservationScope")), "global scope missing");
                    result = observedWaitTasks("task_present", args(90, 9), observation);
                    check(findTask(result, 90) == other && queries == 2, "outside display became permanently unknown");
                    result = observedWaitTasks("task_present", args(90, null), observation);
                    check(findTask(result, 90) == other && queries == 3, "unscoped task outside cache disappeared");
                    activeDisplay = -1;
                    for (String condition : List.of("task_present", "app_ready", "app_crashed",
                            "app_not_responding", "system_dialog_visible")) {
                        result = observedWaitTasks(condition, args(10, 0), observation);
                        check(findTask(result, 10) == phone, "no-active observation lost " + condition);
                        check(Boolean.TRUE.equals(observation.values.get("taskObservationRecheck")),
                                "no-active observation cannot progress without callbacks");
                    }
                    global = new TaskRepository.Snapshot(List.of(), true, "");
                    result = observedWaitTasks("task_absent", args(90, null), observation);
                    check(result != null && result.available && result.tasks.isEmpty(),
                            "authoritative global absence became unknown");
                    global = new TaskRepository.Snapshot(List.of(), false, "shell lost");
                    check(observedWaitTasks("task_absent", args(90, null), observation) == null,
                            "unavailable global snapshot became verified absence");
                    check("unknown".equals(observation.values.get("taskObservationState")),
                            "unavailable state not exposed");
                    activeDisplay = 4;
                    published = null;
                    global = new TaskRepository.Snapshot(List.of(), true, "");
                    int before = queries;
                    check(observedWaitTasks("task_absent", args(90, 4), observation) == null,
                            "unknown covered provider became verified absence");
                    check(queries == before, "unknown covered provider triggered a fresh fallback");
                    check("published".equals(observation.values.get("taskObservationSource")),
                            "unknown covered provider lost its provenance");
                    check(observedWaitTasks("task_present", args(41, null), observation) == null,
                            "unknown active task observation was replaced");
                    check(queries == before, "unknown active observation triggered a fallback");
                }
                """ + RuntimeSourceFixture.methods("DesktopAutomationController",
                "observedWaitTasks", "selectObservedTasks", "filterWaitTasks", "findTask"));
    }

    private static JSONObject observe(final String condition, final TaskRepository.Snapshot snapshot,
            final int taskId, final Integer displayId) throws Exception {
        final var args = new JSONObject().put("taskId", taskId);
        if (displayId != null) {
            args.put("displayId", displayId);
        }
        return DesktopAutomationController.observeTaskCondition(
                condition, args, snapshot, new JSONObject());
    }

    private static TaskRepository.TaskEntry task(final int id, final int displayId,
            final boolean active) {
        return new TaskRepository.TaskEntry(id, id, displayId, "test.app", "test.app.Main",
                "test.app.Main", "freeform", new Rect(), false, true, active);
    }
}
