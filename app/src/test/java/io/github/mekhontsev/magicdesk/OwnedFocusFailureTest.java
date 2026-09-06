package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class OwnedFocusFailureTest {
    @Test
    public void failedOwnedSubmissionNeverFallsBack() throws Exception {
        final String area = RuntimeSourceFixture.methods(
                "ShellFullscreenTaskArea", "focusStack", "ownsFocusTarget");
        RuntimeSourceFixture.verify("""
                static class Log { static void d(String tag, String text) {} static void w(String tag, String text) {} static void w(String tag, String text, Throwable error) {} }
                static class HiddenTaskApi {
                    static Object findTask(Object service, int display, int task) { return task; }
                    static Object requireTask(Object service, int display, int task) throws ReflectiveOperationException { return task; }
                    static int getTaskWindowingMode(Object task) { return 1; }
                }
                static int ownedSubmissions, rawSubmissions, samples;
                static class TaskWindowingCommand {
                    static void focusTasks(Object service, int display, int[] tasks) { rawSubmissions++; throw new IllegalStateException("raw fallback"); }
                    static void focusTasksWithSurfaceCommit(Object service, int display, int[] tasks) { focusTasks(service, display, tasks); }
                }
                static class Ownership { boolean isDesktopHostTask(int task) { return true; } boolean isDesktopTask(Object task) { return true; } }
                static class Planes {
                    ShellFullscreenTaskArea.FocusResult focusStack(Object service, int display, int[] tasks, Ownership owner) {
                        ownedSubmissions++; throw new IllegalStateException("owned submission failed");
                    }
                }
                static class ShellFullscreenTaskArea {
                    enum FocusResult { NOT_HANDLED, WORKSPACE_FOREGROUND, FULLSCREEN_FOREGROUND }
                    int mDisplayId = 4;
                    final Ownership mOwnership = new Ownership(); final Planes mPlanes = new Planes();
                    int[] desktopFocusTasks(Object service, int display, int[] tasks) { return tasks; }
                    boolean concealForShowDesktop(int display) { return true; }
                """ + area + "}\n" + """
                static class DesktopWorkspaceCommand {
                    int displayId = 4, targetTaskId = 42; int[] backToFrontTaskIds = {42};
                    void validate() {} String operationName() { return "activate"; }
                    boolean requiresInputFocusCommit() { return true; } boolean presentsDesktop() { return false; }
                }
                static class ShellDesktopFocusController {
                    static class CommitBarrier {}
                    CommitBarrier captureCommitBarrier() { return new CommitBarrier(); }
                    boolean convergeAfterCommit(int task, CommitBarrier barrier, Runnable requester) { throw new AssertionError("convergence after failed submission"); }
                    boolean convergeTaskAfterCommit(int task, CommitBarrier barrier) { throw new AssertionError("convergence after failed submission"); }
                }
                record Result(boolean success, String error) {
                    static Result success(int count) { return new Result(true, ""); }
                    static Result failure(int count, String error) { return new Result(false, error); }
                }
                static final String TAG = "test"; static final int WINDOWING_MODE_FREEFORM = 5;
                final Object mService = this;
                final ShellFullscreenTaskArea mFullscreenTaskArea = new ShellFullscreenTaskArea();
                final ShellDesktopFocusController mFocusController = new ShellDesktopFocusController();
                final Runnable mTaskSampleRequester = () -> samples++;
                public static void verify() {
                    Result result = new Fixture().execute(new DesktopWorkspaceCommand());
                    check(!result.success, "failed submission acknowledged success");
                    check(ownedSubmissions == 1, "owned transaction count changed");
                    check(rawSubmissions == 0, "failed owner authorized raw fallback");
                    check(samples == 0, "failed owner requested convergence");
                    check(result.error.contains("owned submission failed"), "owner failure was replaced");
                }
                """ + RuntimeSourceFixture.methods("ShellDesktopWorkspaceCoordinator",
                "execute", "applyPhysicalOrder", "usefulMessage"));
    }
}
