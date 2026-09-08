package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Executes the existing callback orchestration with deterministic task/handler fakes. */
public final class WindowCompletionRegressionTest {
    @Test
    public void cancellationIsImmediateAndCannotCompleteReplacementTask() throws Exception {
        verify("""
                f.makeFullscreen(task, false, results::add);
                TaskRepository.ActionCallback old = f.submitted;
                f.cancelPendingTransitions("session stopped");
                check(results.size() == 1 && !results.get(0).success, "cancellation was not immediate");
                f.mTaskStates.states.clear();
                f.makeFullscreen(task, false, results::add);
                old.onComplete(new TaskRepository.ActionResult(true, "late"));
                check(results.size() == 1, "old acknowledgement completed new request");
                check(f.mTaskStates.find(42).active, "old acknowledgement changed replacement state");
                f.submitted.onComplete(new TaskRepository.ActionResult(true, "new"));
                check(results.size() == 2 && results.get(1).success, "new request lost completion");
                """);
    }

    @Test
    public void duplicateSuccessDoesNotRepeatCompletionOrStateUpdates() throws Exception {
        verify("""
                f.makeFullscreen(task, false, results::add);
                f.submitted.onComplete(new TaskRepository.ActionResult(true, "ok"));
                int updates = f.mRuntimeState.updates;
                f.submitted.onComplete(new TaskRepository.ActionResult(true, "duplicate"));
                check(results.size() == 1 && results.get(0).success, "success completed more than once");
                check(updates == f.mRuntimeState.updates, "duplicate result changed state");
                """);
    }

    @Test
    public void taskRemovalCompletesFullscreenOnceWithoutLateStateUpdates() throws Exception {
        verify("""
                f.makeFullscreen(task, false, results::add);
                f.forgetTaskState(42);
                check(results.size() == 1 && !results.get(0).success,
                        "task removal lost cancellation completion");
                int updates = f.mRuntimeState.updates;
                f.submitted.onComplete(new TaskRepository.ActionResult(true, "late"));
                f.submitted.onComplete(new TaskRepository.ActionResult(true, "duplicate"));
                check(results.size() == 1, "completion ran more than once");
                check(updates == f.mRuntimeState.updates, "late result changed runtime state");
                """);
    }

    @Test
    public void stoppedSessionResultCancelsInsteadOfDisappearing() throws Exception {
        verify("""
                f.makeFullscreen(task, false, results::add);
                f.mRuntimeState.running = false;
                f.mTaskStates.states.clear();
                f.submitted.onComplete(new TaskRepository.ActionResult(true, "late"));
                check(results.size() == 1 && !results.get(0).success,
                        "stale session result lost cancellation completion");
                check(f.mRuntimeState.updates == 0, "stale session changed runtime state");
                """);
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static class Rect {}
                static class TaskRepository {
                    interface ActionCallback { void onComplete(ActionResult result); }
                    record ActionResult(boolean success, String message) {}
                    static class TaskEntry { int taskId = 42; Rect bounds = new Rect(); }
                }
                static class DesktopTaskRuntimeState {
                    boolean active;
                    boolean beginFullscreenTransition() { if (active) return false; active = true; return true; }
                    boolean isFullscreenTransition() { return active; }
                    void finishFullscreenTransition() { active = false; }
                    void setFullscreenRestoreBounds(Rect bounds) {}
                    void clearFullscreenRestoreBounds() {}
                    void setAppRequestedFullscreen(boolean value) {}
                }
                static class States {
                    final Map<Integer, DesktopTaskRuntimeState> states = new HashMap<>();
                    DesktopTaskRuntimeState state(int id) { return states.computeIfAbsent(id, key -> new DesktopTaskRuntimeState()); }
                    DesktopTaskRuntimeState find(int id) { return states.get(id); }
                    void forget(int id) { states.remove(id); }
                    boolean isCurrent(int id, DesktopTaskRuntimeState state) { return states.get(id) == state; }
                }
                static class RuntimeState {
                    boolean running = true; int updates;
                    int displayId() { return 4; }
                    boolean isRunning() { return running; }
                    void scheduleRefresh() { updates++; }
                }
                static class NativeBounds {
                    Rect getTaskbarMaximizedBounds() { return new Rect(); }
                    void clearForFullscreen(int taskId) {}
                }
                static class DisplayState {
                    List<Object> visibleTasks() { return List.of(); }
                    void beginFullscreenTransition(List<Object> tasks, int id) {}
                }
                static class Handler { void post(Runnable runnable) { runnable.run(); } }
                static class Log { static void w(String tag, String message) {} static void w(String tag, String message, Throwable error) {} }
                static class BuiltInDesktopAppCatalog {
                    static boolean remembersWindowState(Object task) { return false; }
                }
                record AppReference(String key) {}
                static class AppProfile { AppReference reference(Object task) { return new AppReference("0|example.app"); } }
                final AppProfile mAppProfile = new AppProfile();
                static class AppWindowState { enum Mode { FULLSCREEN } }
                static class AppWindowStateStore { static void rememberMode(AppReference key, AppWindowState.Mode mode) {} }
                static class DesktopWindowTransitionRequest {
                    static DesktopWindowTransitionRequest enterAppFullscreen(int display, int task, Rect bounds, int density, String reason) { return new DesktopWindowTransitionRequest(); }
                    static DesktopWindowTransitionRequest enterFullscreen(int display, int task, int density, String reason) { return new DesktopWindowTransitionRequest(); }
                }
                final States mTaskStates = new States();
                final RuntimeState mRuntimeState = new RuntimeState();
                final NativeBounds mNativeWindowBounds = new NativeBounds();
                final DisplayState mDisplayTaskState = new DisplayState();
                final Handler mHandler = new Handler();
                final Map<DesktopTaskRuntimeState, TaskRepository.ActionCallback> mFullscreenCompletions = new LinkedHashMap<>();
                static final String TAG = "test";
                TaskRepository.ActionCallback submitted;
                void rememberWindowed(TaskRepository.TaskEntry task, Rect bounds, Rect workArea) {}
                int densityFor(TaskRepository.TaskEntry task) { return 0; }
                void submitRequired(DesktopWindowTransitionRequest request, TaskRepository.ActionCallback callback) { submitted = callback; }
                boolean hasFullscreenTransitions() { return mTaskStates.states.values().stream().anyMatch(s -> s.active); }
                void finishWorkspaceTransition(int display, boolean success) { mRuntimeState.updates++; }
                public static void verify() throws Exception {
                    Fixture f = new Fixture();
                    TaskRepository.TaskEntry task = new TaskRepository.TaskEntry();
                    List<TaskRepository.ActionResult> results = new ArrayList<>();
                """ + scenario + "}\n" + RuntimeSourceFixture.methods(
                "DesktopWindowTransitionController", "makeFullscreen", "forgetTaskState",
                "complete", "completeFullscreen", "cancelPendingTransitions"));
    }
}
