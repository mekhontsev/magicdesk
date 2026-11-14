package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class TaskGeometrySubmissionTest {
    @Test public void singleNativeResizeRetainsItsDirectPath() throws Exception {
        verify("""
                adapted = false;
                place(10);
                check(nativeCalls == 1 && transitionCalls == 0 && transactions == 0,
                        "native resize acquired a WCT");
                check(events.equals(List.of("read:10", "plan:10", "native:10")),
                        "native path gained waits: " + events);
                """);
    }

    @Test public void adaptedResizeRetainsSystemSurfaceOwnership() throws Exception {
        verify("""
                place(10);
                check(nativeCalls == 0 && transitionCalls == 1 && transactions == 1,
                        "adapted placement was not one system transition");
                check(events.equals(List.of("barrier", "read:10", "plan:10", "transaction",
                        "geometry:10", "change", "barrier")), "wrong commit order: " + events);
                check(last.placements.equals(List.of(10)), "wrong transaction targets");
                """);
    }

    @Test public void batchUsesTheSameCommitWithOrWithoutFirmwareGeometry() throws Exception {
        for (boolean adapted : new boolean[] {false, true}) {
            verify("adapted = " + adapted + ";" + """
                    place(10, 20);
                    check(nativeCalls == 0 && transitionCalls == 1 && transactions == 1,
                            "batch split into independent commits");
                    check(events.equals(List.of("barrier", "read:10", "plan:10", "read:20", "plan:20",
                            "transaction", "geometry:10", "geometry:20", "change", "barrier")),
                            "batch was not validated before submission: " + events);
                    check(last.placements.equals(List.of(10, 20)), "batch geometry incomplete");
                    """);
        }
    }

    @Test public void missingOrNonFreeformPeerRejectsTheWholeBatch() throws Exception {
        for (String rejection : new String[] {"missing = 20;", "nonFreeform = 20;"}) {
            verify(rejection + """
                    try { place(10, 20); throw new AssertionError("invalid target accepted"); }
                    catch (IllegalStateException expected) { }
                    check(transactions == 0 && transitionCalls == 0 && nativeCalls == 0,
                            "partial batch submitted");
                    """);
        }
    }

    @Test public void failedTransitionDoesNotReplayOrAcknowledgeThePlacement() throws Exception {
        verify("""
                failTransition = true;
                try { place(10); throw new AssertionError("failed transition acknowledged"); }
                catch (ReflectiveOperationException expected) { }
                check(transitionCalls == 1 && nativeCalls == 0 && transactions == 1,
                        "failed transition retried through another path");
                check(barriers == 1 && events.get(events.size() - 1).equals("change"),
                        "failed transition reached completion");
                """);
    }

    @Test public void barrierFailuresRemainFailuresWithoutReplay() throws Exception {
        for (int failingBarrier : new int[] {1, 2}) {
            verify("failBarrier = " + failingBarrier + ";" + """
                    try { place(10); throw new AssertionError("failed barrier acknowledged"); }
                    catch (ReflectiveOperationException expected) { }
                    int expectedSubmissions = failBarrier == 1 ? 0 : 1;
                    check(transactions == expectedSubmissions && transitionCalls == expectedSubmissions
                            && nativeCalls == 0, "barrier failure changed submission: " + events);
                    """);
        }
    }

    @Test public void recoveryPublishesModeGeometryAndVisibilityThroughOneTransition() throws Exception {
        for (int mode : new int[] {1, 5}) {
            verify("int mode = " + mode + ";" + """
                    expectedReason = "restore-prepared-task";
                    restorePreparedTask(service, 7, 10, mode, new FrameworkWindowGeometry.Plan(10));
                    check(transitionCalls == 1 && barriers == 2, "recovery bypassed transition");
                    check(last.placements.equals(List.of(10)), "lost recovery geometry");
                    check(last.mode == mode && Boolean.FALSE.equals(last.hidden) && last.reordered,
                            "incomplete recovery");
                    check(events.equals(List.of("barrier", "read:10", "transaction", "mode",
                            "geometry:10", "hidden", "reorder", "caption", "change", "barrier")),
                            "wrong recovery order: " + events);
                    """);
        }
    }

    @Test public void failedRecoverySubmissionIsNotReplayed() throws Exception {
        verify("""
                expectedReason = "restore-prepared-task";
                failTransition = true;
                try {
                    restorePreparedTask(service, 7, 10, 5, new FrameworkWindowGeometry.Plan(10));
                    throw new AssertionError("recovery failure swallowed");
                } catch (ReflectiveOperationException expected) { }
                check(transitionCalls == 1 && barriers == 1 && transactions == 1,
                        "uncertain recovery replayed");
                """);
    }

    @Test public void legacyMinimizeRetainsSystemLayerAssignment() throws Exception {
        verify("""
                expectedReason = "select-minimize-successor";
                expectedTransition = ShellWindowTransitionExecutor.SystemTransition.TO_FRONT;
                minimize(7, 10, 20);
                check(transitionCalls == 1 && barriers == 1 && transactions == 1,
                        "minimize bypassed system layer assignment");
                check(last.order.equals(List.of("10:false:false", "20:true:true")),
                        "minimize changed ordering intent");
                check(last.placements.isEmpty() && last.mode == 0 && last.hidden == null,
                        "minimize changed geometry or visibility");
                """);
    }

    private static void verify(String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static final int WINDOWING_MODE_FREEFORM = 5;
                static final Object service = new Object();
                static final List<String> events = new ArrayList<>();
                static boolean adapted = true, failTransition;
                static int transactions, nativeCalls, transitionCalls, barriers, failBarrier;
                static int missing = -1, nonFreeform = -1;
                static String expectedReason = "place-desktop-windows";
                static ShellWindowTransitionExecutor.SystemTransition expectedTransition =
                        ShellWindowTransitionExecutor.SystemTransition.CHANGE;
                static Transaction last;
                static class Rect {
                    int left, top, right, bottom;
                    Rect(int l, int t, int r, int b) { left=l; top=t; right=r; bottom=b; }
                }
                static class Transaction {
                    final List<Integer> placements = new ArrayList<>();
                    final List<String> order = new ArrayList<>();
                    int mode;
                    Boolean hidden;
                    boolean reordered;
                }
                static class HiddenTaskApi {
                    static Object getService() { return service; }
                    static Object requireTaskToken(Object s, int display, int id) {
                        return requireTask(s, display, id);
                    }
                    static Object requireTask(Object s, int display, int id) {
                        check(s == service && display == 7, "target changed");
                        events.add("read:" + id);
                        if (id == missing) throw new IllegalStateException("task missing");
                        return id;
                    }
                    static int getTaskWindowingMode(Object task) { return task.equals(nonFreeform) ? 1 : 5; }
                    static Object getTaskToken(Object task) { return task; }
                    static void resizeTaskBounds(Object s, int display, int id, Rect bounds) {
                        check(s == service && display == 7 && bounds.bottom == 678, "native target changed");
                        nativeCalls++; events.add("native:" + id);
                    }
                }
                static class FrameworkWindowGeometry {
                    static boolean adapted() { return adapted; }
                    static Plan plan(Object task, Rect bounds) {
                        events.add("plan:" + task);
                        check(bounds.bottom == 678, "requested geometry changed");
                        return new Plan(task);
                    }
                    static class Plan {
                        final Object task;
                        Plan(Object t) { task=t; }
                        void apply(FrameworkWindowingApi w, Object tx, Object token) {
                            check(task.equals(token) && tx == last, "geometry split across transactions");
                            ((Transaction)tx).placements.add((Integer)token);
                            events.add("geometry:" + token);
                        }
                    }
                }
                static class FrameworkRuntime {
                    static FrameworkRuntime current() { return new FrameworkRuntime(); }
                    FrameworkWindowingApi windowing() { return new FrameworkWindowingApi(); }
                }
                static class FrameworkWindowingApi {
                    Object newTransaction() {
                        transactions++; events.add("transaction"); return last = new Transaction();
                    }
                    Class<?> transactionClass() { return Transaction.class; }
                    void setWindowingMode(Object tx, Object token, int mode) {
                        ((Transaction)tx).mode = mode; events.add("mode");
                    }
                    void setHidden(Object tx, Object token, boolean hidden) {
                        ((Transaction)tx).hidden = hidden; events.add("hidden");
                    }
                    void reorder(Object tx, Object token, boolean top) {
                        reorder(tx, token, top, false);
                    }
                    void reorder(Object tx, Object token, boolean top, boolean parents) {
                        ((Transaction)tx).reordered = true;
                        ((Transaction)tx).order.add(token + ":" + top + ":" + parents);
                        events.add("reorder");
                    }
                }
                static class TaskCaptionInsetsCommand {
                    static void addCaptionInsetOperation(Object tx, Object token, boolean exclude) {
                        check(exclude == (((Transaction)tx).mode != 5), "wrong caption policy");
                        events.add("caption");
                    }
                }
                static class FrameworkWindowCommitBarrier {
                    static void awaitSystemTransitions() throws ReflectiveOperationException {
                        barriers++; events.add("barrier");
                        if (barriers == failBarrier) throw new ReflectiveOperationException("barrier failed");
                    }
                }
                static class ShellWindowTransitionExecutor {
                    enum SystemTransition { CHANGE, TO_FRONT }
                    static void startForShellAdoption(int display, SystemTransition kind,
                            Class<?> c, Object tx, String reason)
                            throws ReflectiveOperationException {
                        check(display == 7 && kind == expectedTransition
                                && c == Transaction.class && tx == last, "commit target changed");
                        check(expectedReason.equals(reason), "wrong submission reason");
                        transitionCalls++; events.add("change");
                        if (failTransition) throw new ReflectiveOperationException("transition failed");
                    }
                }
                static void place(int... ids) throws Exception {
                    Rect[] bounds = new Rect[ids.length];
                    for (int i=0; i<ids.length; i++) bounds[i] = new Rect(i*1280, 0, (i+1)*1280, 678);
                    resizeBounds(service, 7, ids, bounds);
                }
                """ + RuntimeSourceFixture.methods("TaskWindowingCommand", "validateGeometry", "resizeBounds", "minimize")
                + RuntimeSourceFixture.methods("ShellPreparedTaskTransition", "restorePreparedTask")
                + "public static void verify() throws Exception { " + scenario + " }");
    }
}
