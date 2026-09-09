package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class NativeFullscreenAdoptionTest {
    @Test
    public void homeLeafAndRootHaveDifferentIds() throws Exception {
        verify("""
                FrameworkTaskSnapshot home = task(10, 1);
                home.rootTaskId = 9;
                f.tasks = List.of(f.target, home);
                f.adopt();
                check(f.order.equals(List.of(9, 100)), "HOME leaf confused with its root");
                check(f.mCommittedPlaneLayers.get(42) > 0, "foreground adopted below HOME");
                """);
    }

    @Test
    public void backgroundAdoptionReordersHomeRootNotItsLeaf() throws Exception {
        verify("""
                FrameworkTaskSnapshot home = task(10, 1);
                home.rootTaskId = 9;
                f.target.focused = false;
                f.tasks = List.of(home, f.target);
                f.adopt();
                check(f.order.equals(List.of(100, 9)), "HOME root not retained above background");
                check(f.mCommittedPlaneLayers.get(42) < 0, "background adopted above HOME");
                """);
    }

    @Test
    public void missingHomeDoesNotGuessPlacement() throws Exception {
        verify("""
                f.tasks = List.of(f.target);
                try { f.adopt(); throw new AssertionError("failure expected"); }
                catch (IllegalStateException expected) {}
                check(f.moves == 0 && f.parked == 1, "unknown HOME placement was guessed");
                """);
    }

    @Test
    public void foregroundNativeFullscreenKeepsItsPlaceAndIdentity() throws Exception {
        verify("""
                f.tasks = List.of(f.target, task(10, 1));
                f.adopt();
                check(f.moves == 1 && f.mPlanes.containsKey(42), "task not adopted");
                check(f.order.equals(List.of(10, 100)), "adoption changed root order");
                check(f.mCommittedPlaneLayers.get(42) > 0, "foreground plane below HOME");
                check(f.confirmations == 1 && f.mUnconfirmedPlanes.isEmpty(), "adoption not confirmed");
                f.adopt();
                check(f.moves == 1 && f.acquired == 1 && barriers == 1, "duplicate event repeated adoption");
                """);
    }

    @Test
    public void phoneTaskAboveBackgroundFullscreenRetainsFocusAndOrder() throws Exception {
        verify("""
                f.mDisplayId = 0;
                f.target.focused = false;
                f.tasks = List.of(task(77, 1), task(10, 1), f.target);
                f.adopt();
                check(f.order.equals(List.of(100, 10, 77)), "phone foreground was raised over");
                check(!f.planeFocusable, "background plane made focusable");
                check(f.mCommittedPlaneLayers.get(42) < 0, "background plane appeared above HOME");
                check(f.planesBelowWorkspace(), "background became composed foreground");
                """);
    }

    @Test
    public void freeformForegroundIsNotCoveredByAdoption() throws Exception {
        verify("""
                f.target.focused = false;
                f.tasks = List.of(task(50, 1), f.target, task(10, 1));
                f.adopt();
                check(f.order.equals(List.of(10, 100, 50)), "freeform foreground was covered");
                check(!f.planeFocusable, "adoption selected its background target");
                """);
    }

    @Test
    public void ignoresUnownedOrAlreadyWindowedTasksAndWrongDisplay() throws Exception {
        verify("""
                f.owned = false;
                f.adopt();
                f.owned = true;
                f.target.windowingMode = 5;
                f.adopt();
                f.target.windowingMode = 1;
                f.adoptFullscreenTask(f, 99, 42, f.ownership);
                check(f.acquired == 0 && barriers == 0 && f.moves == 0, "irrelevant task mutated");
                """);
    }

    @Test
    public void modeChangedWhileNativeTransitionFinishedCancelsAdoption() throws Exception {
        verify("""
                f.afterBarrier = () -> f.target.windowingMode = 5;
                f.adopt();
                check(f.moves == 0 && f.mPlanes.isEmpty(), "late event forced fullscreen");
                check(f.parked == 1 && f.mAvailablePlanes.size() == 1, "unused reservation leaked");
                """);
    }

    @Test
    public void removedTaskDoesNotGetRecreated() throws Exception {
        verify("""
                f.afterBarrier = () -> f.tasks = List.of(task(10, 1));
                f.adopt();
                check(f.moves == 0 && f.parked == 1, "removed task was adopted");
                """);
    }

    @Test
    public void failedSubmissionRetainsUnconfirmedReservation() throws Exception {
        verify("""
                f.failSubmission = true;
                try { f.adopt(); throw new AssertionError("failure expected"); }
                catch (IllegalStateException expected) {}
                check(f.mPlanes.containsKey(42) && f.mUnconfirmedPlanes.size() == 1, "uncertain ownership lost");
                check(f.mAvailablePlanes.isEmpty(), "uncertain plane published as reusable");
                """);
    }

    @Test
    public void workspaceOrderDeduplicatesRootsAndExcludesChrome() throws Exception {
        verify("""
                FrameworkTaskSnapshot child = task(61, 1);
                child.rootTaskId = 60;
                check(adoptionWorkspaceOrder(
                        List.of(task(90, 20001), child, task(60, 1),
                                task(31, 20002), task(32, 20002), f.target, task(10, 1)),
                        1, Map.of(20002, 30)).equals(List.of(10, 42, 30, 60)),
                        "workspace order confused task roots, areas or chrome");
                """);
    }

    @Test
    public void adoptionRetainsBothSidesOfHomeAcrossPlaneRelease() throws Exception {
        verify("""
                Map<Integer, Integer> old = new LinkedHashMap<>();
                old.put(11, 1);
                old.put(12, 2);
                Map<Integer, Integer> layers = adoptionSurfaceLayers(old, List.of(42, 10, 11, 12), 42, true);
                check(layers.get(42) < 0 && layers.get(11) > 0 && layers.get(12) > layers.get(11),
                        "background adoption changed existing foreground");
                f.mPlanes.put(42, new TaskDisplayAreaHandle());
                f.mCommittedPlaneLayers.putAll(layers);
                f.applySurfaceLayers(f.mCommittedPlaneLayers, f.mPlanes);
                check(f.mCommittedPlaneLayers.equals(Map.of(42, -1)), "release lost retained background layer");
                check(f.planesBelowWorkspace(), "removed foreground retained composed background");
                """);
    }

    @Test
    public void adoptionBetweenExistingFullscreenPeersPreservesTheirOrder() throws Exception {
        verify("""
                Map<Integer, Integer> old = new LinkedHashMap<>();
                old.put(11, 1);
                old.put(12, 2);
                Map<Integer, Integer> layers = adoptionSurfaceLayers(old, List.of(10, 11, 42, 12), 42, false);
                check(new ArrayList<>(layers.keySet()).equals(List.of(11, 42, 12)), "existing plane order changed");
                """);
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static final String TAG = "fixture";
                static final int WINDOWING_MODE_FULLSCREEN = 1;
                static Fixture current;
                static int barriers;
                static class FrameworkTaskSnapshot {
                    int taskId, rootTaskId, displayAreaFeatureId, windowingMode = 1;
                    boolean focused = true;
                    Object task = this;
                }
                static FrameworkTaskSnapshot task(int id, int area) {
                    FrameworkTaskSnapshot t = new FrameworkTaskSnapshot();
                    t.taskId = t.rootTaskId = id; t.displayAreaFeatureId = area; return t;
                }
                static class ShellDesktopTaskOwnership {
                    boolean isDesktopTask(Object task) { return current.owned; }
                    int desktopHostTaskId() { return 10; }
                }
                static class HiddenTaskApi {
                    static Object findTask(Object service, int display, int id) { return current.target; }
                    static int getTaskWindowingMode(Object task) { return ((FrameworkTaskSnapshot) task).windowingMode; }
                    static Object getTaskToken(Object task) { return ((FrameworkTaskSnapshot) task).taskId; }
                    static Object requireRootTaskToken(Object service, int display, int id) { return id; }
                }
                static class TaskDisplayAreaHandle {
                    Object token() { return 100; }
                    int featureId() { return 20010; }
                }
                static class FrameworkTaskSnapshotSource {
                    static List<FrameworkTaskSnapshot> readWindowState(Object service, int display, int limit) {
                        check(display == current.mDisplayId, "snapshot changed display");
                        return current.tasks;
                    }
                }
                static class FrameworkWindowCommitBarrier {
                    static void awaitSystemTransitions() { barriers++; current.afterBarrier.run(); }
                }
                static class FrameworkWindowingApi {
                    Class<?> transactionClass() { return Object.class; }
                    Object newTransaction() { return new Object(); }
                    void setWindowingMode(Object tx, Object token, int mode) {
                        check(token.equals(100) && mode == 1, "adoption changed application mode");
                    }
                    void setFocusable(Object tx, Object token, boolean focused) { current.planeFocusable = focused; }
                    void reparent(Object tx, Object token, Object parent, boolean top) {
                        check(token.equals(42) && parent.equals(100), "adoption moved another task");
                        current.moves++;
                        current.order.remove(Integer.valueOf(42));
                    }
                    void reorder(Object tx, Object token, boolean top) { reorder(tx, token, top, false); }
                    void reorder(Object tx, Object token, boolean top, boolean parents) {
                        check(top && !parents, "adoption raised ancestor");
                        current.order.remove((Integer) token);
                        current.order.add((Integer) token);
                    }
                }
                static class FrameworkRuntime {
                    static FrameworkRuntime current() { return new FrameworkRuntime(); }
                    FrameworkWindowingApi windowing() { return new FrameworkWindowingApi(); }
                }
                static class ShellWindowTransitionExecutor {
                    static void applyAtomic(Object service, Class<?> type, Object tx) throws ReflectiveOperationException {
                        if (current.failSubmission) throw new IllegalStateException("uncertain submission");
                    }
                }
                static class SurfaceOrder { void applyLayers(Map<TaskDisplayAreaHandle, Integer> layers) {} }
                static class Log {
                    static void i(String tag, String message) {}
                    static void w(String tag, String message, Throwable error) {}
                }
                final Map<Integer, TaskDisplayAreaHandle> mPlanes = new LinkedHashMap<>();
                final Map<TaskDisplayAreaHandle, Integer> mPlaneAnchorTaskIds = new LinkedHashMap<>();
                final Set<TaskDisplayAreaHandle> mUnconfirmedPlanes = new HashSet<>();
                final List<TaskDisplayAreaHandle> mAvailablePlanes = new ArrayList<>();
                final List<Integer> mPlaneOrder = new ArrayList<>();
                final Map<Integer, Integer> mCommittedPlaneLayers = new LinkedHashMap<>();
                final SurfaceOrder mSurfaceOrder = new SurfaceOrder();
                int mDisplayId = 4, acquired, parked, moves, confirmations;
                Object mService;
                boolean owned = true, planeFocusable, failSubmission;
                final ShellDesktopTaskOwnership ownership = new ShellDesktopTaskOwnership();
                FrameworkTaskSnapshot target = task(42, 1);
                List<FrameworkTaskSnapshot> tasks = List.of(target, task(10, 1));
                List<Integer> order = new ArrayList<>();
                Runnable afterBarrier = () -> {};
                boolean ownsTask(int id) { return mPlanes.containsKey(id); }
                TaskDisplayAreaHandle acquirePlane(Object service, int display) {
                    acquired++; return new TaskDisplayAreaHandle();
                }
                void parkPlane(Object service, TaskDisplayAreaHandle plane) throws ReflectiveOperationException {
                    parked++; mAvailablePlanes.add(plane);
                }
                void waitForTaskInsidePlane(Object service, int display, int id, int feature) {
                    check(display == mDisplayId && id == 42 && feature == 20010, "wrong parent confirmation");
                    confirmations++;
                }
                void adopt() {
                    order = adoptionWorkspaceOrder(tasks, 1, Map.of());
                    adoptFullscreenTask(this, mDisplayId, 42, ownership);
                }
                static int[] toIntArray(List<Integer> values) { return values.stream().mapToInt(Integer::intValue).toArray(); }
                public static void verify() throws Exception {
                    Fixture f = current = new Fixture();
                """ + scenario + "}\n" + RuntimeSourceFixture.methods(
                "ShellFullscreenTaskPlanes", "adoptFullscreenTask",
                "adoptionWorkspaceOrder", "adoptionSurfaceLayers",
                "applySurfaceLayers", "planesBelowWorkspace"));
    }
}
