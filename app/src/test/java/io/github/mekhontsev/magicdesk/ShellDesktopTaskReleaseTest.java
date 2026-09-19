package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class ShellDesktopTaskReleaseTest {
    @Test public void releaseIsOneTransitionAndNeverTouchesIndependentTasks() throws Exception {
        RuntimeSourceFixture.verify("""
                static final int WINDOWING_MODE_FULLSCREEN = 1;
                int mDisplayId = 7;
                final Map<Integer, TaskDisplayAreaHandle> mPlanes = new LinkedHashMap<>();
                static final List<String> writes = new ArrayList<>();
                static final List<String> events = new ArrayList<>();
                static int submissions;
                static class Rect { }
                static class TaskDisplayAreaHandle {
                    enum Parent { DEFAULT_TASK_CONTAINER; int featureId() { return 1; } }
                    int featureId() { return 20001; }
                    Object token() { return "plane"; }
                }
                static class FrameworkTaskSnapshot {
                    int taskId; Object task; int windowingMode;
                    FrameworkTaskSnapshot(int id) { taskId = id; task = id; windowingMode = id == 21 ? 1 : 5; }
                }
                static class FrameworkTaskSnapshotSource {
                    static List<FrameworkTaskSnapshot> readWindowState(Object s, int d, int limit) {
                        return List.of(new FrameworkTaskSnapshot(21), new FrameworkTaskSnapshot(99),
                                new FrameworkTaskSnapshot(22));
                    }
                }
                static class HiddenTaskApi {
                    static Object getTaskToken(Object task) { return task; }
                    static Object requireRootTaskToken(Object s, int d, int t) { return t; }
                }
                static class FrameworkRuntime {
                    static FrameworkRuntime current() { return new FrameworkRuntime(); }
                    FrameworkWindowingApi windowing() { return new FrameworkWindowingApi(); }
                }
                static class FrameworkWindowingApi {
                    Object newTransaction() { return new Object(); }
                    Class<?> transactionClass() { return Object.class; }
                    void setWindowingMode(Object tx, Object token, int mode) {
                        check(mode == 1, "release is not fullscreen"); writes.add("mode:" + token);
                    }
                    void setBounds(Object tx, Object token, Rect bounds) { writes.add("bounds:" + token); }
                    void setForceTranslucent(Object tx, Object token, boolean v) {
                        check(!v, "translucency retained"); writes.add("opaque:" + token);
                    }
                    void setHidden(Object tx, Object token, boolean v) { check(!v, "task hidden"); }
                    void setFocusable(Object tx, Object token, boolean v) { check(v, "focus override retained"); }
                    void reparent(Object tx, Object token, Object parent, boolean top) {
                        check(parent == null && top, "unexpected reparent"); writes.add("reparent:" + token);
                    }
                    void reorder(Object tx, Object token, boolean top, boolean parents) {
                        check(top && !parents, "release raised parents"); writes.add("order:" + token);
                    }
                }
                static class DesktopTaskDensity {
                    static final int INHERIT = -1;
                    static void apply(Object w, Object tx, Object token, int density) {
                        check(density == INHERIT, "Desktop DPI retained"); writes.add("density:" + token);
                    }
                }
                static class TaskCaptionInsetsCommand {
                    static void addCaptionInsetOperation(Object tx, Object token, boolean exclude) {
                        check(exclude, "fullscreen caption retained");
                    }
                }
                static class ShellWindowTransitionExecutor {
                    enum SystemTransition { CHANGE }
                    static void startForShellAdoption(int d, SystemTransition type, Class<?> c, Object tx, String reason) {
                        check(d == 7, "wrong display"); submissions++; events.add("submit");
                    }
                }
                static class FrameworkWindowCommitBarrier {
                    static void awaitSystemTransitions() { events.add("commit"); }
                }
                static class TaskDisplayAreaLaunchCommand {
                    static void waitForTaskWindowingMode(Object s, int d, int t, int mode) { events.add("mode:" + t); }
                }
                boolean ownsTask(int t) { return mPlanes.containsKey(t); }
                static List<Integer> adoptionWorkspaceOrder(List<FrameworkTaskSnapshot> tasks,
                        int area, Map<Integer, Integer> planes) {
                    check(area == 1 && planes.get(20001) == 21, "wrong workspace mapping");
                    return List.of(22, 99, 21);
                }
                void waitForTaskOutsidePlane(Object s, int d, int t, int area) { events.add("outside:" + t); }
                void releasePlane(Object s, int t) { events.add("release:" + t); mPlanes.remove(t); }
                public static void verify() throws Exception {
                    Fixture f = new Fixture(); f.mPlanes.put(21, new TaskDisplayAreaHandle());
                    f.releaseToAndroid(null, 7, new int[]{22, 21});
                    check(submissions == 1, "one transition per window");
                    check(writes.stream().noneMatch(w -> w.endsWith(":99") && !w.equals("order:99")),
                            "independent task state changed");
                    check(writes.contains("reparent:21") && !writes.contains("reparent:22"), "wrong topology owner");
                    check(writes.stream().filter(w -> w.startsWith("order:"))
                            .toList().equals(List.of("order:22", "order:99", "order:21")),
                            "workspace order changed: " + writes);
                    check(events.equals(List.of("submit", "commit", "mode:21", "outside:21", "release:21", "mode:22")),
                            "unexpected release operation or plane released before commit: " + events);
                    submissions = 0; events.clear(); writes.clear();
                    f.releaseToAndroid(null, 7, new int[]{21, 22});
                    check(submissions == 1 && writes.stream().noneMatch(w -> w.startsWith("order:")),
                            "ordinary roots must keep their position during mode change");
                    submissions = 0; events.clear(); writes.clear();
                    try { f.releaseToAndroid(null, 7, new int[]{21, 404}); throw new AssertionError("missing task accepted"); }
                    catch (IllegalStateException expected) { check(submissions == 0, "partial selection submitted"); }
                    try { f.releaseToAndroid(null, 8, new int[]{21}); throw new AssertionError("wrong workspace accepted"); }
                    catch (IllegalArgumentException expected) { check(submissions == 0, "other workspace mutated"); }
                }
                """ + RuntimeSourceFixture.methods("ShellFullscreenTaskPlanes", "releaseToAndroid"));
    }
}
