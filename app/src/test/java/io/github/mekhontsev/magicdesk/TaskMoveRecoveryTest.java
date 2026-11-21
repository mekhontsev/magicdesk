package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class TaskMoveRecoveryTest {
    private static void verify(String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static final int WINDOWING_MODE_FREEFORM = 5, WINDOWING_MODE_FULLSCREEN = 1;
                static final List<String> calls = new ArrayList<>();
                static int currentDisplay = 4, settledDisplay = 7;
                static boolean failBarrier, missing;
                static class Display { static final int INVALID_DISPLAY = -1; }
                static class Rect { }
                static class FrameworkWindowGeometry {
                    static class Plan { final Rect bounds = new Rect(); Rect displayBounds() { return bounds; } }
                }
                static final FrameworkWindowGeometry.Plan geometry = new FrameworkWindowGeometry.Plan();
                static class DesktopTaskDensity { static final int INHERIT = 0, UNCHANGED = -1; }
                static class FrameworkWindowCommitBarrier {
                    static void awaitSystemTransitions() throws ReflectiveOperationException {
                        calls.add("barrier");
                        if (failBarrier) throw new ReflectiveOperationException("barrier failed");
                        currentDisplay = settledDisplay;
                    }
                }
                static class HiddenTaskApi {
                    static Object findTask(Object service, int display, int task) {
                        calls.add("find");
                        check(display == -1 && task == 42, "recovery changed task identity");
                        return missing ? null : currentDisplay;
                    }
                    static int getTaskDisplayId(Object task) { return (Integer)task; }
                }
                static class ShellPreparedTaskTransition {
                    static void restorePreparedTask(Object service, int display, int task, int mode,
                            FrameworkWindowGeometry.Plan plan) throws ReflectiveOperationException {
                        // Production preparation also waits before resolving the task token.
                        FrameworkWindowCommitBarrier.awaitSystemTransitions();
                        check(display == currentDisplay, "recovery chose a stale source display");
                        check(plan == geometry, "recovery lost captured geometry");
                        calls.add("restore:" + mode);
                    }
                }
                static void moveExistingTaskToDisplay(Object service, int task, int from, int to,
                        int mode, Rect bounds, int density, FrameworkWindowGeometry.Plan plan) {
                    check(from == currentDisplay && to == 4 && task == 42, "wrong recovery direction");
                    check(plan == geometry && bounds == geometry.bounds, "recovery lost captured geometry");
                    calls.add("move:" + mode); currentDisplay = to;
                }
                static void moveExistingTaskAsFullscreen(Object service, int task, int from, int to, int density) {
                    check(from == currentDisplay && to == 4 && task == 42, "wrong fullscreen recovery direction");
                    calls.add("move:1"); currentDisplay = to;
                }
                static void waitForTaskFreeformBounds(Object service, int display, int task, Rect bounds) {
                    calls.add("bounds");
                }
                static void waitForTaskWindowingMode(Object service, int display, int task, int mode) {
                    calls.add("mode");
                }
                static void waitForTaskVisibility(Object service, int display, int task, boolean visible) {
                    calls.add("visible");
                }
                """ + RuntimeSourceFixture.methods("TaskDisplayAreaLaunchCommand", "restoreFailedTaskMove")
                + "public static void verify() throws Exception {" + scenario + "}");
    }

    @Test public void delayedMoveIsObservedBeforeSelectingRecoveryDisplay() throws Exception {
        for (int mode : new int[]{1, 5}) {
            verify("int mode = " + mode + ";" + """
                    restoreFailedTaskMove(new Object(), 42, 4, mode, geometry);
                    check(calls.equals(List.of("barrier", "find", "move:" + mode,
                            mode == 5 ? "bounds" : "mode", "visible")), "wrong recovery order: " + calls);
                    """);
        }
    }

    @Test public void taskRemainingOnSourceUsesTheLocalRecovery() throws Exception {
        verify("""
                settledDisplay = 4;
                restoreFailedTaskMove(new Object(), 42, 4, 5, geometry);
                check(calls.equals(List.of("barrier", "find", "barrier", "restore:5")),
                        "source recovery changed: " + calls);
                """);
    }

    @Test public void disappearedTaskIsNotRecreated() throws Exception {
        verify("""
                missing = true;
                restoreFailedTaskMove(new Object(), 42, 4, 5, geometry);
                check(calls.equals(List.of("barrier", "find")), "missing task was mutated: " + calls);
                """);
    }

    @Test public void failedBarrierDoesNotChooseRecoveryFromStaleState() throws Exception {
        verify("""
                failBarrier = true;
                try {
                    restoreFailedTaskMove(new Object(), 42, 4, 5, geometry);
                    throw new AssertionError("barrier failure swallowed");
                } catch (ReflectiveOperationException expected) {
                    check(calls.equals(List.of("barrier")), "stale task state used: " + calls);
                }
                """);
    }
}
