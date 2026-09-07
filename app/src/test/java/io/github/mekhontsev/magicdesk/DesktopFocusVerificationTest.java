package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Exercises the production commit path independently of firmware repair. */
public final class DesktopFocusVerificationTest {
    @Test
    public void disabledRepairRetainsBarriersWithoutSchedulingCallbackWork() throws Exception {
        RuntimeSourceFixture.verify("""
                static class FrameworkInputWindowObservationSource {
                    long checkpoint() { return 17; }
                    boolean isAvailable() { return true; }
                }
                static class Executor {
                    int submissions;
                    void execute(Runnable action) { submissions++; }
                }
                record CommitBarrier(long taskSampleGeneration, long inputWindowGeneration,
                        boolean inputWindowEventsAvailable) {}
                static class ShellDesktopFocusController {
                    final Object mPendingLock = new Object();
                    java.util.function.BooleanSupplier mRepairEnabled = () -> false;
                    final FrameworkInputWindowObservationSource mInputWindowObservations =
                            new FrameworkInputWindowObservationSource();
                    final Executor mExecutor = new Executor();
                    long mTaskSampleGeneration = 11;
                    int mPendingFocusedTaskId = -1, mFocusConfirmationTaskId = -1;
                    boolean mAcceptingEvents = true, mPendingConfirmationRequested, mDrainScheduled;
                    void drainFocusChanges() {}
                """ + RuntimeSourceFixture.methods("ShellDesktopFocusController",
                "captureCommitBarrier", "enqueueFocusReconciliation") + """
                }
                public static void verify() {
                    for (boolean repair : new boolean[]{false, true}) {
                        final ShellDesktopFocusController controller = new ShellDesktopFocusController();
                        controller.mRepairEnabled = () -> repair;
                        final CommitBarrier barrier = controller.captureCommitBarrier();
                        check(barrier.taskSampleGeneration == 11, "task checkpoint lost");
                        check(barrier.inputWindowGeneration == 17 && barrier.inputWindowEventsAvailable,
                                "repair policy disconnected input commit events");
                        controller.enqueueFocusReconciliation(42, true);
                        check(controller.mExecutor.submissions == (repair ? 1 : 0),
                                "disabled repair scheduled background work");
                    }
                }
                """);
    }

    @Test
    public void verificationRemainsRequiredWithoutRepair() throws Exception {
        RuntimeSourceFixture.verify("""
                static final String TAG = "test";
                static class Display { static final int DEFAULT_DISPLAY = 0, INVALID_DISPLAY = -1; }
                static class Log {
                    static void w(String tag, String message) {}
                    static void w(String tag, String message, Throwable error) {}
                }
                static class ComponentName {
                    String getPackageName() { return "test"; }
                    String getClassName() { return "Home"; }
                }
                static class FrameworkTaskSnapshot { static final int WINDOWING_MODE_FULLSCREEN = 1; }
                static class HiddenTaskApi {
                    static Object findTask(Object service, int display, int id) throws ReflectiveOperationException {
                        return taskExists ? id : null;
                    }
                    static ComponentName getTaskTopActivity(Object task) { return new ComponentName(); }
                    static int getTaskActivityType(Object task) { return homeTarget ? 2 : 1; }
                    static int getTaskWindowingMode(Object task) { return 1; }
                    static boolean isTaskVisible(Object task) { return taskVisible; }
                }
                static class FrameworkInputSnapshotSource {
                    static String readLocal() throws IOException, InterruptedException { return "input"; }
                }
                static class TaskInputWindowParser {
                    static String describeFocus(String state, int display) { return state; }
                }
                static class TaskWindowingCommand {
                    static void focusTasksWithinCurrentParent(Object service, int display, int[] tasks) { reorders++; }
                }
                static class InputWindows { boolean isAvailable() { return true; } }
                record CommitBarrier(long taskSampleGeneration, long inputWindowGeneration,
                        boolean inputWindowEventsAvailable) {}
                final Object mPendingLock = new Object(), mTaskService = new Object();
                final InputWindows mInputWindowObservations = new InputWindows();
                java.util.function.BooleanSupplier mRepairEnabled = () -> false;
                int mDisplayId = -1, mMissingWindowRepairTaskId = -1;
                static boolean focused, taskExists = true, taskVisible = true, sampleReady = true,
                        homeTarget, repairSucceeds;
                static int probes, repairs, reorders, samples;
                void clearConfigurationOnWorker() { mDisplayId = -1; }
                boolean awaitTaskSample(long generation) throws InterruptedException { return sampleReady; }
                boolean awaitCommittedInputFocus(int display, int task, long generation, boolean events)
                        throws IOException, InterruptedException {
                    probes++; return focused;
                }
                static boolean isInputFocused(int display, int task) { probes++; return focused; }
                static boolean isDesktopHostTarget(int type, String pkg, String component) { return type == 2; }
                long inputWindowGeneration() { return 1; }
                long inputFocusRefreshGeneration() { return 1; }
                long taskSampleGeneration() { return 1; }
                boolean awaitInputFocusRefresh(int task, long generation) { return true; }
                boolean repairMissingInputTarget(int display, int task, Object raw) {
                    repairs++; focused = repairSucceeds; return true;
                }
                public static void verify() {
                    final Fixture fixture = new Fixture();
                    final CommitBarrier barrier = new CommitBarrier(0, 0, true);
                    final Runnable requestSample = () -> samples++;
                    for (boolean repair : new boolean[]{false, true}) {
                        fixture.mRepairEnabled = () -> repair;
                        fixture.configureOnWorker(4);
                        check(fixture.mDisplayId == 4, "repair policy disabled verification display");
                        for (boolean home : new boolean[]{false, true}) {
                            homeTarget = home;
                            focused = true;
                            probes = repairs = reorders = samples = 0;
                            check(fixture.convergeAfterCommitOnWorker(42, barrier, requestSample),
                                    "confirmed input rejected");
                            check(probes == 1 && repairs == 0 && samples == 0,
                                    "consistent focus needs only verification");
                            focused = false;
                            repairSucceeds = true;
                            check(fixture.convergeAfterCommitOnWorker(42, barrier, requestSample) == repair,
                                    "missing input acknowledged without repair");
                            check(repairs == (repair ? 1 : 0), "repair ignored its policy");
                            check(samples == (repair ? 1 : 0), "verification-only path resampled");
                            check(reorders == 0, "unnecessary hierarchy repair");
                        }
                        taskExists = false;
                        check(!fixture.convergeAfterCommitOnWorker(42, barrier, requestSample),
                                "missing task acknowledged");
                        taskExists = true;
                        focused = true;
                        sampleReady = false;
                        probes = 0;
                        check(!fixture.convergeAfterCommitOnWorker(42, barrier, requestSample),
                                "unobserved task commit acknowledged");
                        check(probes == 0, "input checked before task commit");
                        check(!fixture.convergeTaskAfterCommitOnWorker(42, barrier),
                                "unobserved structural commit acknowledged");
                        sampleReady = true;
                        taskVisible = false;
                        check(!fixture.convergeTaskAfterCommitOnWorker(42, barrier),
                                "invisible structural target acknowledged");
                        taskVisible = true;
                        check(fixture.convergeTaskAfterCommitOnWorker(42, barrier),
                                "visible committed structural target rejected");
                        fixture.configureOnWorker(-1);
                        check(!fixture.convergeAfterCommitOnWorker(42, barrier, requestSample),
                                "inactive focus controller acknowledged success");
                        check(!fixture.convergeTaskAfterCommitOnWorker(42, barrier),
                                "inactive structural controller acknowledged success");
                    }
                }
                """ + RuntimeSourceFixture.methods("ShellDesktopFocusController",
                "configureOnWorker", "convergeAfterCommitOnWorker",
                "convergeTaskAfterCommitOnWorker"));
    }
}
