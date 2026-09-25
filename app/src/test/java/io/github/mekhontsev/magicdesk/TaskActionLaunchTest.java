package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class TaskActionLaunchTest {
    @Test public void intentAndPendingActionsPreservePreparedPresentation() throws Exception {
        verify("""
                for (int display : new int[] {0, 104}) {
                    expectedDisplay = display;
                    for (int mode : new int[] {1, 5}) {
                        current.mode = mode;
                        current.bounds = mode == 5 ? new Rect(491, 191, 1620, 889)
                                : new Rect(0, 0, 1920, 1080);
                        for (int route = 0; route < 3; route++) {
                            dispatch(route);
                            check(submitted.display == display && submitted.task == 42,
                                    "action lost its exact destination");
                            check(submitted.mode == mode, "action inherited display windowing mode");
                            check(submitted.bounds != current.bounds
                                            && submitted.bounds.equals(current.bounds),
                                    "action replaced or borrowed mutable task geometry");
                            check(submitted.area == null, "action tried to change task-area ownership");
                            check(delivery == route, "action changed authorization transport");
                        }
                    }
                }
                check(calls == 12, "actions were retried or omitted");
                """);
    }

    @Test public void unavailablePresentationDoesNotDispatchAnAction() throws Exception {
        verify("""
                failRead = true;
                for (int route = 0; route < 3; route++) {
                    try { dispatch(route); throw new AssertionError("missing geometry accepted"); }
                    catch (ReflectiveOperationException expected) { }
                }
                check(calls == 0, "unknown presentation fell back to display defaults");
                """);
    }

    @Test public void missingAndForeignTasksCannotReceiveIntent() throws Exception {
        verify("""
                current = null;
                try { dispatch(0); throw new AssertionError("missing task accepted"); }
                catch (IllegalArgumentException expected) { }
                current = new Task();
                current.packageName = "other.application";
                try { dispatch(0); throw new AssertionError("foreign task accepted"); }
                catch (IllegalArgumentException expected) { }
                check(calls == 0, "invalid target received an action");
                """);
    }

    private static void verify(String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static final Object service = new Object();
                static final Intent intent = new Intent();
                static final PendingIntent pending = new PendingIntent();
                static final IActivityLaunchCallback callback = new IActivityLaunchCallback();
                static Task current = new Task();
                static ActivityOptions submitted;
                static int expectedDisplay = 104, calls, delivery;
                static boolean failRead;
                record Rect(int left, int top, int right, int bottom) {
                    Rect(Rect value) { this(value.left, value.top, value.right, value.bottom); }
                }
                static class Task {
                    int mode = 5;
                    Rect bounds = new Rect(491, 191, 1620, 889);
                    String packageName = "fixture.application";
                }
                static class ComponentName { String getPackageName() { return "fixture.application"; } }
                static class Intent { ComponentName getComponent() { return new ComponentName(); } }
                static class PendingIntent { }
                static class IActivityLaunchCallback { }
                static class ActivityOptions {
                    int display = -1, task = -1, mode;
                    Rect bounds;
                    Object area;
                    static ActivityOptions makeBasic() { return new ActivityOptions(); }
                    void setLaunchDisplayId(int value) { display = value; }
                    void setLaunchBounds(Rect value) { bounds = value; }
                }
                static class HiddenTaskApi {
                    static Object findTask(Object value, int display, int id) {
                        check(value == service && display == expectedDisplay && id == 42, "task query scope");
                        return current;
                    }
                    static String getTaskPackage(Object task) { return ((Task) task).packageName; }
                    static int getTaskWindowingMode(Object task) { return ((Task) task).mode; }
                    static Rect readBounds(Object task) throws ReflectiveOperationException {
                        if (failRead) throw new ReflectiveOperationException("task configuration unavailable");
                        return ((Task) task).bounds;
                    }
                }
                static class FrameworkActivityLaunchApi {
                    static void setTask(ActivityOptions options, int id) { options.task = id; }
                    static void setWindowingMode(ActivityOptions options, int mode) { options.mode = mode; }
                    static void setTaskDisplayArea(ActivityOptions options, Object area) { options.area = area; }
                }
                static class FrameworkWindowingCompat {
                    static FrameworkWindowingCompat current() { return new FrameworkWindowingCompat(); }
                    void allowFlexibleLaunchSize(ActivityOptions options) { }
                }
                static Intent createExactAppIntent(Intent value) { return value; }
                static void launchActivity(Object target, Intent value, ActivityOptions options) {
                    check(target == service && value == intent, "intent payload changed");
                    submitted = options; delivery = 0; calls++;
                }
                static void sendPendingIntent(IActivityLaunchCallback target, PendingIntent value,
                        ActivityOptions options) {
                    check(target == callback && value == pending, "pending callback changed");
                    submitted = options; delivery = 1; calls++;
                }
                static void sendCreatorAuthorizedPendingIntent(PendingIntent value, ActivityOptions options) {
                    check(value == pending, "creator-authorized payload changed");
                    submitted = options; delivery = 2; calls++;
                }
                static void dispatch(int route) throws ReflectiveOperationException {
                    if (route == 0) launchTaskAction(service, expectedDisplay, 42, intent);
                    else if (route == 1)
                        launchPendingIntentTaskAction(expectedDisplay, 42, current, pending, callback);
                    else launchCreatorAuthorizedPendingIntentTaskAction(expectedDisplay, 42, current, pending);
                }
                public static void verify() throws Exception {
                """ + scenario + "}\n" + RuntimeSourceFixture.methods("TaskDisplayAreaLaunchCommand",
                "launchTaskAction", "launchPendingIntentTaskAction", "launchCreatorAuthorizedPendingIntentTaskAction",
                "taskActionOptions", "existingTaskOptions"));
    }
}
