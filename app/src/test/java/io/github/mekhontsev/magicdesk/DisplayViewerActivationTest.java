package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DisplayViewerActivationTest {
    @Test public void reopenActivatesExistingTaskThroughItsCurrentOwner() throws Exception {
        RuntimeSourceFixture.verify("""
                static class BuiltInWindowLauncher { interface Callback { void onComplete(Throwable error); } }
                static class Session { boolean closed; final Display output = new Display(); }
                static class Display { int id = 3; int getDisplayId() { return id; } }
                static class android {
                    static class app {
                        static class ActivityManager {
                            static class AppTask extends Task { AppTask(int id) { super(id); } }
                            final List<AppTask> tasks = new ArrayList<>();
                            List<AppTask> getAppTasks() { return tasks; }
                        }
                    }
                }
                static class Task {
                    int taskId, moves;
                    Task(int id) { taskId = id; }
                    Task getTaskInfo() { return this; }
                    void moveToFront() { moves++; }
                }
                static class DesktopRuntimeBridge {
                    static boolean managed;
                    static boolean hasWorkspace(int id) { return managed; }
                }
                static class Result { boolean success = true; String message = "ok"; }
                static class MagicDeskRuntime {
                    static int focused = -1;
                    static void focusDesktopTask(int displayId, int taskId,
                            java.util.function.Consumer<Result> callback) {
                        check(displayId == 3, "wrong focus display");
                        focused = taskId; callback.accept(new Result());
                    }
                }
                final Session mSession = new Session();
                final Display display = new Display();
                final android.app.ActivityManager manager = new android.app.ActivityManager();
                boolean isFinishing() { return false; }
                Display getDisplay() { return display; }
                int getTaskId() { return 7; }
                android.app.ActivityManager getSystemService(Class<?> type) { return manager; }
                public static void verify() {
                    Fixture f = new Fixture();
                    var other = new android.app.ActivityManager.AppTask(6);
                    var viewer = new android.app.ActivityManager.AppTask(7);
                    f.manager.tasks.add(other); f.manager.tasks.add(viewer);
                    List<Throwable> results = new ArrayList<>();
                    f.show(results::add);
                    check(viewer.moves == 1 && other.moves == 0 && results.get(0) == null,
                            "ordinary viewer did not activate its exact AppTask");
                    DesktopRuntimeBridge.managed = true;
                    f.show(results::add);
                    check(viewer.moves == 1 && MagicDeskRuntime.focused == 7 && results.get(1) == null,
                            "managed viewer bypassed Desktop focus gateway");
                    f.display.id = 0;
                    f.show(results::add);
                    check(results.get(2) != null && viewer.moves == 1, "migrated viewer was raised");
                }
                """ + RuntimeSourceFixture.methods("DisplayViewerActivity", "show"));
    }
}
