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
                    int featureId() { return 20001; }
                }
                static class FrameworkTaskSnapshot {
                    int taskId; Object task;
                    FrameworkTaskSnapshot(int id) { taskId = id; task = id; }
                }
                static class FrameworkTaskSnapshotSource {
                    static List<FrameworkTaskSnapshot> readWindowState(Object s, int d, int limit) {
                        return List.of(new FrameworkTaskSnapshot(21), new FrameworkTaskSnapshot(99),
                                new FrameworkTaskSnapshot(22));
                    }
                }
                static class HiddenTaskApi { static Object getTaskToken(Object task) { return task; } }
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
                        check(parent == null && !top, "release activated a task"); writes.add("reparent:" + token);
                    }
                    void reorder(Object tx, Object token, boolean top) {
                        check(!top, "release activated a task"); writes.add("bottom:" + token);
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
                void waitForTaskOutsidePlane(Object s, int d, int t, int area) { events.add("outside:" + t); }
                void releasePlane(Object s, int t) { events.add("release:" + t); mPlanes.remove(t); }
                public static void verify() throws Exception {
                    Fixture f = new Fixture(); f.mPlanes.put(21, new TaskDisplayAreaHandle());
                    f.releaseToAndroid(null, 7, new int[]{22, 21});
                    check(submissions == 1, "one transition per window");
                    check(writes.stream().noneMatch(w -> w.endsWith(":99")), "independent task changed");
                    check(writes.contains("reparent:21") && !writes.contains("reparent:22"), "wrong topology owner");
                    check(writes.indexOf("bottom:21") < writes.indexOf("bottom:22"), "relative order reversed");
                    check(events.equals(List.of("submit", "commit", "mode:21", "outside:21", "release:21", "mode:22")),
                            "plane released before observed commit: " + events);
                    submissions = 0; events.clear(); writes.clear();
                    try { f.releaseToAndroid(null, 7, new int[]{21, 404}); throw new AssertionError("missing task accepted"); }
                    catch (IllegalStateException expected) { check(submissions == 0, "partial selection submitted"); }
                    try { f.releaseToAndroid(null, 8, new int[]{21}); throw new AssertionError("wrong workspace accepted"); }
                    catch (IllegalArgumentException expected) { check(submissions == 0, "other workspace mutated"); }
                }
                """ + RuntimeSourceFixture.methods("ShellFullscreenTaskPlanes", "releaseToAndroid"));
    }
}
