package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class PointerWindowCommandsTest {
    @Test public void backWaitsForTheCapturedTaskFocus() throws Exception {
        verify("""
                var task = new TaskRepository.TaskEntry(2, 17);
                backToTask(task);
                check(MagicDeskRuntime.target == 17 && TaskRepository.backDisplay == -1, "Back preceded focus");
                MagicDeskRuntime.complete(true);
                check(TaskRepository.backDisplay == 2, "Back went to another display");
                """);
    }

    @Test public void rejectedFocusNeverSendsBackToAnotherApplication() throws Exception {
        verify("""
                backToTask(new TaskRepository.TaskEntry(2, 17));
                MagicDeskRuntime.complete(false);
                check(TaskRepository.backDisplay == -1 && !mActivity.status.isEmpty(), "rejected focus still sent Back");
                """);
    }

    @Test public void staleDisplayTargetDoesNotSendAnyCommand() throws Exception {
        verify("""
                backToTask(new TaskRepository.TaskEntry(3, 17));
                check(MagicDeskRuntime.target == -1 && TaskRepository.backDisplay == -1, "stale display was targeted");
                """);
    }

    @Test public void arrangingUsesTheExactMenuTask() throws Exception {
        verify("""
                arrangeTask(new TaskRepository.TaskEntry(2, 17), 8);
                check(MagicDeskRuntime.target == 17 && MagicDeskRuntime.arrangement == 8, "arranged another task");
                """);
    }

    private static void verify(String scenario) throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static class R { static class string { static int status_switch_failed = 1; } }
                static class TaskRepository {
                    static int backDisplay = -1;
                    record ActionResult(boolean success, String message) {}
                    interface ActionCallback { void onComplete(ActionResult result); }
                    static class TaskEntry {
                        final int displayId, taskId;
                        TaskEntry(int displayId, int taskId) { this.displayId = displayId; this.taskId = taskId; }
                    }
                    static void sendBackToDisplay(int display, ActionCallback callback) {
                        backDisplay = display; callback.onComplete(new ActionResult(true, ""));
                    }
                }
                static class Activity {
                    String status = "";
                    int getCurrentDisplayId() { return 2; }
                    boolean isActivityUnavailable() { return false; }
                    void runOnUiThread(Runnable action) { action.run(); }
                    String getString(int id, String detail) { return detail; }
                    void setStatus(String value) { status = value; }
                }
                static class MagicDeskRuntime {
                    static int target = -1, arrangement = -1;
                    static TaskRepository.ActionCallback pending;
                    static void focusDesktopTask(int display, int task, TaskRepository.ActionCallback callback) {
                        target = task; pending = callback;
                    }
                    static void complete(boolean success) {
                        pending.onComplete(new TaskRepository.ActionResult(success, success ? "" : "unavailable"));
                    }
                    static boolean arrangeTask(int display, int task, int action) { target = task; arrangement = action; return true; }
                }
                static Activity mActivity = new Activity();
                static void clearInteractionStack() {}
                public static void verify() throws Exception { new Controller().run(); }
                static class Controller {
                void run() throws Exception {
                """ + scenario + "}\n" + RuntimeSourceFixture.methods("AppTaskController",
                        "arrangeTask", "backToTask", "reportBackResult") + "}");
    }
}
