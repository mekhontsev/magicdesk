package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class FullscreenSubmissionRegressionTest {
    @Test
    public void laterAcquisitionFailureReleasesEarlierUnsubmittedReservation() throws Exception {
        verify("""
                f.failAcquisition = 2;
                try { f.apply(); throw new AssertionError("failure expected"); }
                catch (IllegalStateException expected) {}
                check(f.mAvailablePlanes.size() == 1, "earlier unsubmitted reservation was leaked");
                check(ShellWindowTransitionExecutor.submissions == 0, "acquisition failure submitted a transaction");
                """);
    }

    @Test
    public void postCommitSurfaceFailureRetainsOwnedSlots() throws Exception {
        verify("""
                f.failSurface = true;
                try { f.apply(); throw new AssertionError("failure expected"); }
                catch (IllegalStateException expected) {}
                check(ShellWindowTransitionExecutor.submissions == 1, "expected one owned submission");
                check(f.mAvailablePlanes.isEmpty(), "committed slot was returned to free pool");
                check(f.mPlanes.size() == 2, "committed residency ownership was lost");
                """);
    }

    @Test
    public void failedSubmissionRemainsReservedUntilObservationConfirmsIt() throws Exception {
        verify("""
                ShellWindowTransitionExecutor.fail = true;
                try { f.apply(); throw new AssertionError("failure expected"); }
                catch (IllegalStateException expected) {}
                check(ShellWindowTransitionExecutor.submissions == 1, "expected one owned submission");
                check(f.mAvailablePlanes.isEmpty(), "uncertain submission published free slots");
                check(f.mPlanes.size() == 2, "uncertain submission lost reservation identity");
                """);
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static class Rect {}
                static class TaskDisplayAreaHandle {
                    final int id; TaskDisplayAreaHandle(int id) { this.id = id; }
                    Object token() { return id; } int featureId() { return id; }
                }
                static class FrameworkWindowingApi {
                    Class<?> transactionClass() { return Object.class; }
                    Object newTransaction() { return new Object(); }
                    void setFocusable(Object transaction, Object token, boolean value) {}
                    void setWindowingMode(Object transaction, Object token, int value) {}
                    void reparent(Object transaction, Object token, Object parent, boolean top) {}
                    void setBounds(Object transaction, Object token, Rect bounds) {}
                    void reorder(Object transaction, Object token, boolean top, boolean parents) {}
                }
                static class FrameworkRuntime {
                    static FrameworkRuntime current() { return new FrameworkRuntime(); }
                    FrameworkWindowingApi windowing() { return new FrameworkWindowingApi(); }
                }
                static class HiddenTaskApi {
                    static Object requireTaskToken(Object service, int display, int id) { return id; }
                    static Object requireTask(Object service, int display, int id) { return id; }
                    static int getTaskWindowingMode(Object task) { return 1; }
                }
                static class DesktopTaskDensity {
                    static final int UNCHANGED = -1;
                    static void apply(FrameworkWindowingApi api, Object transaction, Object token, int density) {}
                }
                static class TaskCaptionInsetsCommand { static void addCaptionInsetOperation(Object transaction, Object token, boolean excluded) {} }
                static class ShellWindowTransitionExecutor {
                    static int submissions; static boolean fail;
                    static void applyAtomic(Object service, Class<?> type, Object transaction) throws ReflectiveOperationException {
                        submissions++; if (fail) throw new IllegalStateException("submission failed");
                    }
                    static void applySelection(Object service, int display, Class<?> type, Object transaction, int mode) throws ReflectiveOperationException { applyAtomic(service, type, transaction); }
                }
                static class TaskDisplayAreaLaunchCommand {
                    static void moveExistingTaskAsFullscreen(Object service, int display, int task, Object token) {}
                    static void waitForTaskWindowingMode(Object service, int display, int task, int mode) {}
                }
                static class Log { static void i(String tag, String message) {} }
                static class MixedStackOrder { boolean fullscreenForeground; int fullscreenTaskId; }
                static final String TAG = "test";
                static final int WINDOWING_MODE_FULLSCREEN = 1;
                final Map<Integer, TaskDisplayAreaHandle> mPlanes = new LinkedHashMap<>();
                final List<TaskDisplayAreaHandle> mAvailablePlanes = new ArrayList<>();
                final List<Integer> mPlaneOrder = new ArrayList<>();
                final Set<TaskDisplayAreaHandle> mUnconfirmedPlanes = new LinkedHashSet<>();
                boolean mConcealedForShowDesktop, failSurface;
                int acquired, failAcquisition;
                void discardStalePlaneRecords(Object service, int display) {}
                TaskDisplayAreaHandle acquirePlane(Object service, int display) throws ReflectiveOperationException {
                    if (++acquired == failAcquisition) throw new IllegalStateException("acquisition failed");
                    return new TaskDisplayAreaHandle(acquired);
                }
                void waitForTaskInsidePlane(Object service, int display, int task, int feature) {}
                int[] completeStableOrder(List<Integer> known, int[] requested, Set<Integer> tasks) { return requested; }
                int[] mixedSurfaceOrder(int[] order, MixedStackOrder mixed) { return order; }
                boolean crossesFullscreenPlaneBoundary(int[] order, Set<Integer> tasks) { return false; }
                void addOrderOperations(Object service, int display, int[] order, FrameworkWindowingApi api, Object transaction, Map<Integer, TaskDisplayAreaHandle> planes, Object unused, boolean crosses) {}
                void addMixedOrderOperations(Object service, int display, MixedStackOrder mixed, int[] order, FrameworkWindowingApi api, Object transaction, Map<Integer, TaskDisplayAreaHandle> planes) {}
                void setPlaneSurfacesVisible(boolean visible) {}
                void applySurfaceOrder(int[] order, Map<Integer, TaskDisplayAreaHandle> planes, boolean below) {
                    if (failSurface) throw new IllegalStateException("surface commit failed");
                }
                void apply() throws ReflectiveOperationException {
                    applyStableOrder(this, 4, List.of(41, 42), new int[]{41, 42}, -1, false, -1, null);
                }
                public static void verify() throws Exception {
                    Fixture f = new Fixture();
                """ + scenario + "}\n" + RuntimeSourceFixture.methods(
                "ShellFullscreenTaskPlanes", "applyStableOrder"));
    }
}
