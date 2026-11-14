package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopTaskInputIntentTest {
    @Test public void pendingIntentIsScopedToTheLiveCommandAndDisplay() throws Exception {
        RuntimeSourceFixture.verify("""
                boolean mRunning = true, mTaskWatcherReady = true;
                int mDisplayId = 7, mFocusingTaskId = 42;
                static Fixture owner;
                static class Queue { boolean running; boolean isRunning() { return running; } }
                final Queue mWorkspaceQueue = new Queue();
                static class DesktopTaskRuntime {
                    int pendingTaskFocus(int display) { return owner.pendingTaskFocus(display); }
                }
                static DesktopTaskRuntime desktopTasks(int display) {
                    return owner == null ? null : new DesktopTaskRuntime();
                }
                public static void verify() {
                    Fixture f = owner = new Fixture();
                    check(taskInputFocusTarget(7, 10) == 10, "idle tracked focus replaced observation");
                    f.mWorkspaceQueue.running = true;
                    check(taskInputFocusTarget(7, 10) == 42, "pending command lost input eligibility");
                    check(taskInputFocusTarget(8, 10) == 10, "intent escaped its display");
                    f.mWorkspaceQueue.running = false;
                    check(taskInputFocusTarget(7, 10) == 10, "completed command masked observation");
                    f.mWorkspaceQueue.running = true; f.mFocusingTaskId = -1;
                    check(taskInputFocusTarget(7, 10) == 10, "unrelated command guessed focus");
                    f.mFocusingTaskId = 42; f.mTaskWatcherReady = false;
                    check(taskInputFocusTarget(7, 10) == 10, "unavailable watcher retained intent");
                    f.mTaskWatcherReady = true; f.mRunning = false;
                    check(taskInputFocusTarget(7, 10) == 10, "stopped runtime retained intent");
                    owner = null;
                    check(taskInputFocusTarget(7, -1) == -1, "missing runtime invented focus");
                }
                """ + RuntimeSourceFixture.methods("DesktopTaskController", "pendingTaskFocus")
                + RuntimeSourceFixture.methods("MagicDeskRuntime", "taskInputFocusTarget"));
    }
}
