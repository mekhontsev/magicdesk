package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class FrameworkActivityLaunchApiTest {
    @Test public void existingTaskPlacementChangesGeometryAtomicallyButFocusOnlyReorders() throws Exception {
        RuntimeSourceFixture.verify("""
                record Rect(int value) { }
                static class ActivityOptions { Object toBundle() { return this; } }
                static ActivityOptions options(int display, boolean fullscreen, Rect bounds) {
                    if (display < 0) throw new IllegalArgumentException("invalid display");
                    return new ActivityOptions();
                }
                static class FrameworkTaskSnapshot { static final int ACTIVITY_TYPE_STANDARD = 1; }
                static class DesktopTaskDensity { static final int UNCHANGED = -1; }
                static class HiddenTaskApi {
                    static int mode = 1, user, type = 1, starts, result;
                    static Object requireTask(Object service, int display, int task) { return new Object(); }
                    static int getTaskUserId(Object task) { return user; }
                    static int getTaskActivityType(Object task) { return type; }
                    static int getTaskWindowingMode(Object task) { return mode; }
                    static Rect readBounds(Object task) { return new Rect(42); }
                    static int startActivityFromRecents(Object s, int task, Object bundle) { starts++; return result; }
                }
                static class TaskDisplayAreaLaunchCommand {
                    static int changes;
                    static void moveExistingTaskAsFullscreen(Object s, int task, int from, int to, int dpi) {
                        check(task == 12 && dpi == -1, "task identity or density changed");
                        changes++; HiddenTaskApi.mode = 1;
                    }
                    static void moveExistingTaskAsFreeform(Object s, int task, int from, int to, Rect bounds, int dpi) {
                        check(task == 12 && dpi == -1 && bounds != null, "freeform geometry missing");
                        changes++; HiddenTaskApi.mode = 5;
                    }
                }
                """ + RuntimeSourceFixture.methods("FrameworkActivityLaunchApi", "moveTask") + """
                public static void verify() throws Exception {
                    moveTask(new Object(),12,7,7,0,null);
                    check(HiddenTaskApi.starts == 1 && TaskDisplayAreaLaunchCommand.changes == 0, "focus mutated geometry");
                    moveTask(new Object(),12,7,7,0,new Rect(42));
                    check(HiddenTaskApi.mode == 5 && TaskDisplayAreaLaunchCommand.changes == 1, "mode change used launch hint only");
                    moveTask(new Object(),12,7,7,0,new Rect(42));
                    check(HiddenTaskApi.starts == 2 && TaskDisplayAreaLaunchCommand.changes == 1, "freeform focus resized");
                    moveTask(new Object(),12,7,7,0,new Rect(43));
                    check(TaskDisplayAreaLaunchCommand.changes == 2, "new bounds ignored");
                    moveTask(new Object(),12,7,7,0,null);
                    check(HiddenTaskApi.mode == 1 && TaskDisplayAreaLaunchCommand.changes == 3, "fullscreen ignored");
                    moveTask(new Object(),12,7,8,0,null);
                    check(TaskDisplayAreaLaunchCommand.changes == 4, "cross-display transfer was not atomic");
                    for (int invalid : new int[] {1,2}) {
                        HiddenTaskApi.user = invalid == 1 ? 10 : 0; HiddenTaskApi.type = invalid == 2 ? 2 : 1;
                        try { moveTask(new Object(),12,7,8,0,null); throw new AssertionError("invalid task admitted"); }
                        catch (IllegalArgumentException expected) { }
                    }
                    check(TaskDisplayAreaLaunchCommand.changes == 4 && HiddenTaskApi.starts == 2, "invalid task mutated");
                    HiddenTaskApi.user = 0; HiddenTaskApi.type = 1; HiddenTaskApi.result = -1;
                    try { moveTask(new Object(),12,7,7,0,null); throw new AssertionError("rejected focus succeeded"); }
                    catch (IllegalStateException expected) { }
                    check(HiddenTaskApi.starts == 3, "focus failure replayed");
                }
                """);
    }

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
                    Rect() { }
                    Rect(Rect r) { left = r.left; top = r.top; right = r.right; bottom = r.bottom; }
                    boolean isEmpty() { return left >= right || top >= bottom; }
                }
                static class FrameworkTaskSnapshot { static final int ACTIVITY_TYPE_STANDARD = 1; }
                public static class ActivityOptions {
                    int display = -1, mode, type;
                    Rect bounds;
                    static ActivityOptions makeBasic() { return new ActivityOptions(); }
                    void setLaunchDisplayId(int value) { display = value; }
                    public void setLaunchWindowingMode(int value) { mode = value; }
                    public void setLaunchActivityType(int value) { type = value; }
                    void setLaunchBounds(Rect value) { bounds = value; }
                }
                """ + RuntimeSourceFixture.methods("FrameworkActivityLaunchApi", "options", "setWindowingMode", "setActivityType") + """
                public static void verify() throws Exception {
                    for (int display : new int[] {0, 7}) {
                        ActivityOptions fullscreen = options(display, true, null);
                        check(fullscreen.display == display && fullscreen.mode == 1,
                                "fullscreen destination changed");
                        check(fullscreen.bounds != null && fullscreen.bounds.isEmpty(),
                                "fullscreen must explicitly clear saved freeform launch bounds");
                        ActivityOptions inherited = options(display, false, null);
                        check(inherited.display == display && inherited.mode == 0
                                        && inherited.bounds == null,
                                "unspecified presentation must retain Android launch defaults");
                        check(options(display, true, null).bounds != fullscreen.bounds,
                                "mutable launch bounds shared between requests");
                        Rect bounds = new Rect(); bounds.right = 800; bounds.bottom = 600;
                        ActivityOptions windowed = options(display, false, bounds);
                        check(windowed.display == display && windowed.mode == 5 && windowed.type == 1
                                && windowed.bounds.right == 800 && windowed.bounds.bottom == 600
                                && windowed.bounds != bounds, "freeform placement lost or aliased bounds");
                        try { options(display, true, bounds); throw new AssertionError("contradictory mode accepted"); }
                        catch (IllegalArgumentException expected) { }
                        try { options(display, false, new Rect()); throw new AssertionError("empty freeform accepted"); }
                        catch (IllegalArgumentException expected) { }
                    }
                    try {
                        options(-1, true, null);
                        throw new AssertionError("invalid display accepted");
                    } catch (IllegalArgumentException expected) { }
                }
                """);
    }
}
