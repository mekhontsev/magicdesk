package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class WindowReuseAcknowledgementTest {
    @Test
    public void reuseDoesNotPublishLaunchBeforeFocusAcknowledgement() throws Exception {
        verify("""
                check(MagicDeskRuntime.callback != null, "reuse discarded its focus acknowledgement");
                check(!result.ready.isDone(), "reuse completed before focus acknowledgement");
                check(MagicDeskRuntime.notes == 0 && DesktopTaskLaunchDiagnostics.notes == 0,
                        "reuse published launch before focus acknowledgement");
                MagicDeskRuntime.callback.onComplete(new TaskRepository.ActionResult(true, "focused"));
                check(result.ready.join().success, "successful focus was not published");
                check(MagicDeskRuntime.notes == 1 && DesktopTaskLaunchDiagnostics.notes == 1,
                        "successful focus was not recorded exactly once");
                """);
    }

    @Test
    public void failedFocusCannotBecomeReusedLaunchSuccess() throws Exception {
        verify("""
                check(MagicDeskRuntime.callback != null, "reuse discarded its focus acknowledgement");
                MagicDeskRuntime.callback.onComplete(new TaskRepository.ActionResult(false, "focus failed"));
                check(!result.ready.join().success, "failed focus became launch success");
                MagicDeskRuntime.callback.onComplete(new TaskRepository.ActionResult(true, "duplicate"));
                check(!result.ready.join().success, "duplicate acknowledgement replaced failure");
                check(MagicDeskRuntime.notes == 0 && DesktopTaskLaunchDiagnostics.notes == 0,
                        "failed focus published a successful launch");
                """);
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static class TaskRepository {
                    interface ActionCallback { void onComplete(ActionResult result); }
                    record ActionResult(boolean success, String message) {}
                }
                static class MagicDeskRuntime {
                    static TaskRepository.ActionCallback callback; static int notes;
                    static void focusDesktopTask(int display, int task, TaskRepository.ActionCallback value) { callback = value; }
                    static void noteTaskLaunchFocus(int display, int task) { notes++; }
                }
                static class DesktopTaskLaunchDiagnostics {
                    static int notes;
                    static void note(int task, int from, int to, String path) { notes++; }
                }
                static class LaunchResult {
                    final CompletableFuture<TaskRepository.ActionResult> ready;
                    LaunchResult(int task, boolean reused) { ready = CompletableFuture.completedFuture(new TaskRepository.ActionResult(true, "")); }
                    LaunchResult(int task, boolean reused, CompletableFuture<TaskRepository.ActionResult> ready) { this.ready = ready; }
                }
                public static void verify() {
                    LaunchResult result = completeReusedTask(4, 42, 4, "test-reuse");
                """ + scenario + "}\n" + RuntimeSourceFixture.methods(
                "WindowedAppLauncher", "completeReusedTask"));
    }
}
