package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class FrameworkActivityLaunchApiTest {
    @Test public void hiddenOptionsAndBinderCallsPreserveExactArguments() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Intent { }
                static class Bundle { }
                static class IBinder { }
                public static class ActivityOptions {
                    int mode, type, task;
                    Object area;
                    boolean behind;
                    final Bundle bundle = new Bundle();
                    public void setLaunchWindowingMode(int value) { mode = value; }
                    public void setLaunchActivityType(int value) { type = value; }
                    public void setLaunchTaskId(int value) { task = value; }
                    public void setLaunchTaskDisplayArea(Object value) { area = value; }
                    public void setAvoidMoveToFront() { behind = true; }
                    Bundle toBundle() { return bundle; }
                }
                static class FrameworkRuntime {
                    static FrameworkRuntime current() { return new FrameworkRuntime(); }
                    FrameworkRuntime windowing() { return this; }
                    Class<?> tokenClass() { return Object.class; }
                }
                static final Intent intent = new Intent();
                static final ActivityOptions options = new ActivityOptions();
                static int starts, moves;
                public static class Service {
                    public int startActivity(Object caller, String pkg, String feature, Intent value,
                            String mime, IBinder token, String who, int request, int flags,
                            Object profiler, Bundle bundle) {
                        check(caller == null && pkg.equals("com.android.shell") && feature == null
                                && value == intent && mime == null && token == null && who == null
                                && request == -1 && flags == 0 && profiler == null && bundle == options.bundle,
                                "start arguments changed");
                        starts++; return -42;
                    }
                    public void moveTaskToFront(Object caller, String pkg, int task, int flags, Bundle bundle) {
                        check(caller == null && pkg.equals("com.android.shell") && task == 42
                                && flags == 0 && bundle == options.bundle, "fullscreen entry arguments");
                        moves++;
                    }
                }
                public static void verify() throws Exception {
                    Object area = new Object();
                    setWindowingMode(options, 5); setActivityType(options, 1);
                    setTask(options, 42); setTaskDisplayArea(options, area); avoidMoveToFront(options);
                    check(options.mode == 5 && options.type == 1 && options.task == 42
                            && options.area == area && options.behind, "options changed");
                    check(startActivity(new Service(), intent, options) == -42 && starts == 1,
                            "start result must reach launch policy without retry");
                    moveTaskToFront(new Service(), 42, options.bundle);
                    check(moves == 1, "move count");
                }
                """ + (RuntimeSourceFixture.methods("FrameworkActivityLaunchApi", "setWindowingMode",
                        "setActivityType", "setTask", "setTaskDisplayArea", "avoidMoveToFront", "startActivity")
                        + RuntimeSourceFixture.methods("HiddenTaskApi", "moveTaskToFront"))
                        .replace("Class.forName(\"android.app.IApplicationThread\")", "Object.class")
                        .replace("Class.forName(\"android.app.ProfilerInfo\")", "Object.class"));
    }

    @Test public void launchResultIsObservedBeforeFailureIsReported() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Intent { }
                static class ActivityOptions { }
                static int result, calls, observed = -100;
                static class FrameworkActivityLaunchApi {
                    static int startActivity(Object service, Intent intent, ActivityOptions options) {
                        calls++; return result;
                    }
                }
                public static void verify() throws Exception {
                    result = -42;
                    try { launchActivity(new Object(), new Intent(), new ActivityOptions(), v -> observed = v);
                        throw new AssertionError("failed launch accepted"); }
                    catch (IllegalStateException expected) { }
                    check(calls == 1 && observed == -42, "failure bypassed the outcome observer");
                    result = 2;
                    launchActivity(new Object(), new Intent(), new ActivityOptions(), v -> observed = v);
                    check(calls == 2 && observed == 2, "reuse result changed");
                }
                """ + RuntimeSourceFixture.methods("TaskDisplayAreaLaunchCommand", "launchActivity"));
    }

    @Test public void explicitFullscreenDoesNotInheritPersistedFreeformParameters() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Rect {
                    int left, top, right, bottom;
                    boolean isEmpty() { return left >= right || top >= bottom; }
                }
                public static class ActivityOptions {
                    int display = -1, mode;
                    Rect bounds;
                    static ActivityOptions makeBasic() { return new ActivityOptions(); }
                    void setLaunchDisplayId(int value) { display = value; }
                    public void setLaunchWindowingMode(int value) { mode = value; }
                    void setLaunchBounds(Rect value) { bounds = value; }
                }
                """ + RuntimeSourceFixture.methods("FrameworkActivityLaunchApi", "options", "setWindowingMode") + """
                public static void verify() throws Exception {
                    for (int display : new int[] {0, 7}) {
                        ActivityOptions fullscreen = options(display, true);
                        check(fullscreen.display == display && fullscreen.mode == 1,
                                "fullscreen destination changed");
                        check(fullscreen.bounds != null && fullscreen.bounds.isEmpty(),
                                "fullscreen must explicitly clear saved freeform launch bounds");
                        ActivityOptions inherited = options(display, false);
                        check(inherited.display == display && inherited.mode == 0
                                        && inherited.bounds == null,
                                "unspecified presentation must retain Android launch defaults");
                        check(options(display, true).bounds != fullscreen.bounds,
                                "mutable launch bounds shared between requests");
                    }
                    try {
                        options(-1, true);
                        throw new AssertionError("invalid display accepted");
                    } catch (IllegalArgumentException expected) { }
                }
                """);
    }
}
