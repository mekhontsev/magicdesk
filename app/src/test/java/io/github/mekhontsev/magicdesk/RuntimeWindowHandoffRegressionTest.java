package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import org.json.JSONObject;
import org.junit.Test;

/** Caller contracts for the window owner's structured return and launch acknowledgement. */
public final class RuntimeWindowHandoffRegressionTest {
    @Test
    public void taskReturnUsesTypedResultAndRestoresProtectionOnFailure() throws Exception {
        verifyReturnResult("tasks-returned=1 failed=1\n"
                + DesktopTaskReturnResult.encode(7, 1, 1), false);
        verifyReturnResult(DesktopTaskReturnResult.encode(8, 1, 0), false);
        verifyReturnResult("tasks-returned=0 failed=1", false);
        verifyReturnResult(DesktopTaskReturnResult.encode(7, 2, 0), true);
        verifyReturnResult(DesktopTaskReturnResult.encode(7, 0, 0), true);
    }

    @Test
    public void taskReturnKeepsIoFailureAndMissingDisplayCleanup() throws Exception {
        RuntimeSourceFixture.verify(returnFixture() + """
                public static void verify() {
                    ShellAccess.fail=true;
                    returnDesktopTasksToPhone(new DesktopDisplayTarget(7), success -> {
                        check(!success, "I/O failure reported as success");
                        check(MagicDeskRuntime.restored==1, "I/O failure did not restore protection");
                        callbacks++;
                    });
                    check(callbacks==1 && DesktopTaskReturnResult.calls==0, "I/O completion changed");
                    ShellAccess.fail=false;
                    MagicDeskRuntime.displayId=0;
                    returnDesktopTasksToPhone(null, success -> {
                        check(success, "absent desktop should need no return"); callbacks++;
                    });
                    check(callbacks==2 && MagicDeskRuntime.restored==1, "absent display cleanup changed");
                    check(ShellAccess.calls==1, "absent display issued a return command");
                }
                """);
    }

    @Test
    public void builtInLaunchCompletesOnlyAfterReadyAcknowledgement() throws Exception {
        RuntimeSourceFixture.verify(launchFixture() + """
                public static void verify() {
                    Activity activity=new Activity();
                    launch(activity,new Intent(),new AppLaunchTarget(), error -> {
                        check(error==null, "successful acknowledgement failed"); callbacks++;
                    });
                    activity.drainUi();
                    check(callbacks==0, "built-in launch completed before readiness acknowledgement");
                    check(Arrays.equals(WindowedAppLauncher.preserved,new int[]{41}), "preserved tasks changed");
                    check(WindowedAppLauncher.ready.complete(new TaskRepository.ActionResult(true,"ready")),
                            "acknowledgement already completed");
                    check(callbacks==0, "completion bypassed UI dispatch");
                    activity.drainUi();
                    check(callbacks==1, "acknowledged launch did not complete once");
                }
                """);
    }

    @Test
    public void builtInLaunchReportsRejectedAcknowledgementAsIOException() throws Exception {
        RuntimeSourceFixture.verify(launchFixture() + """
                public static void verify() {
                    Activity activity=new Activity();
                    List<Throwable> errors=new ArrayList<>();
                    launch(activity,new Intent(),new AppLaunchTarget(), errors::add);
                    WindowedAppLauncher.ready.complete(new TaskRepository.ActionResult(false,"focus rejected"));
                    activity.drainUi();
                    check(errors.size()==1 && errors.get(0) instanceof IOException,
                            "rejected readiness did not return IOException");
                    check("focus rejected".equals(errors.get(0).getMessage()), "readiness diagnostic lost");
                }
                """);
    }

    @Test
    public void builtInLaunchKeepsShellUnavailableFallback() throws Exception {
        RuntimeSourceFixture.verify(launchFixture() + """
                public static void verify() {
                    ShellAccess.ready=false;
                    Activity activity=new Activity();
                    Intent intent=new Intent();
                    launch(activity,intent,new AppLaunchTarget(), error -> {
                        check(error==null, "shell-unavailable fallback failed"); callbacks++;
                    });
                    activity.drainUi();
                    check(callbacks==1 && activity.started==1, "fallback did not launch and complete");
                    check(intent.flags==7 && activity.startedDisplay==7, "fallback flags/display changed");
                    check(TaskCommandQueue.calls==0 && WindowedAppLauncher.calls==0, "fallback used shell path");
                    activity.startFailure=new IllegalStateException("activity rejected");
                    launch(activity,new Intent(),new AppLaunchTarget(), error -> {
                        check(error==activity.startFailure, "fallback failure lost"); callbacks++;
                    });
                    activity.drainUi();
                    check(callbacks==2, "fallback failure did not complete");
                }
                """);
    }

    @Test
    public void builtInLaunchPreservesImmediateFailuresAndClosedActivitySuppression() throws Exception {
        RuntimeSourceFixture.verify(launchFixture() + """
                public static void verify() {
                    Activity activity=new Activity();
                    WindowedAppLauncher.failure=new IOException("launch rejected");
                    launch(activity,new Intent(),new AppLaunchTarget(), error -> {
                        check(error==WindowedAppLauncher.failure, "synchronous failure lost"); callbacks++;
                    });
                    activity.drainUi();
                    check(callbacks==1, "synchronous failure did not complete");
                    WindowedAppLauncher.failure=null;
                    launch(activity,new Intent(),new AppLaunchTarget(), error -> callbacks++);
                    activity.destroyed=true;
                    WindowedAppLauncher.ready.complete(new TaskRepository.ActionResult(true,"ready"));
                    activity.drainUi();
                    check(callbacks==1, "destroyed activity received late completion");
                }
                """);
    }

    private static void verifyReturnResult(final String output, final boolean success) throws Exception {
        assertEquals(success, DesktopTaskReturnResult.succeeded(output, 7));
        RuntimeSourceFixture.verify(returnFixture() + """
                public static void verify() {
                    ShellAccess.output=%s;
                    DesktopTaskReturnResult.result=%s;
                    returnDesktopTasksToPhone(new DesktopDisplayTarget(7), success -> {
                        check(success==%s, "task return ignored typed command result"); callbacks++;
                    });
                    check(callbacks==1, "task return callback count changed");
                    check(DesktopTaskReturnResult.calls==1, "caller bypassed typed result parser");
                    check(MagicDeskRuntime.disabled==1 && MagicDeskRuntime.restored==%d,
                            "migration protection cleanup changed");
                }
                """.formatted(JSONObject.quote(output), success, success, success ? 0 : 1));
    }

    private static String returnFixture() throws Exception {
        return """
                static final String TAG="test", DESKTOP_TASK_RETURN_COMMAND="task-return";
                static int callbacks;
                interface ResultCallback { void onComplete(boolean success); }
                static class DesktopDisplayTarget { int displayId; DesktopDisplayTarget(int d) { displayId=d; } }
                static class MagicDeskRuntime {
                    static int disabled,restored,displayId=7;
                    static void disableExternalTaskMigrationProtection() { disabled++; }
                    static void restoreExternalTaskMigrationProtection() { restored++; }
                    static int activeDesktopDisplayId() { return displayId; }
                }
                static class Queue { void execute(Runnable action) { action.run(); } }
                static final Queue OPERATIONS=new Queue();
                static class ShellAccess {
                    static String output=""; static boolean fail; static int calls;
                    static String run(String command) throws IOException {
                        calls++; if (fail) throw new IOException("shell failed"); return output;
                    }
                }
                static class AppProcessCommand { static String run(String owner,String arguments) { return arguments; } }
                static class Log {
                    static void w(String tag,String message) {}
                    static void w(String tag,String message,Throwable error) {}
                }
                static class DesktopTaskReturnResult {
                    static int calls; static boolean result;
                    static boolean succeeded(String output,int displayId) {
                        calls++;
                        check(output.equals(ShellAccess.output.trim()) && displayId==7, "typed result arguments changed");
                        return result;
                    }
                }
                """ + RuntimeSourceFixture.methods("DesktopOperations", "returnDesktopTasksToPhone");
    }

    private static String launchFixture() throws Exception {
        return """
                static int callbacks;
                interface Callback { void onComplete(Throwable error); }
                static class Activity {
                    List<Runnable> ui=new ArrayList<>(); int started,startedDisplay;
                    boolean finishing,destroyed; RuntimeException startFailure;
                    Display getDisplay() { return new Display(); }
                    void startActivity(Intent intent,Object options) {
                        if (startFailure!=null) throw startFailure;
                        started++; startedDisplay=(Integer)options;
                    }
                    void runOnUiThread(Runnable action) { ui.add(action); }
                    void drainUi() { for (Runnable action : List.copyOf(ui)) action.run(); ui.clear(); }
                    boolean isFinishing() { return finishing; }
                    boolean isDestroyed() { return destroyed; }
                }
                static class Display { int getDisplayId() { return 7; } }
                static class Intent {
                    static final int FLAG_ACTIVITY_NEW_TASK=1,FLAG_ACTIVITY_NEW_DOCUMENT=2,FLAG_ACTIVITY_MULTIPLE_TASK=4;
                    int flags;
                    void addFlags(int value) { flags|=value; }
                }
                static class ActivityOptions {
                    int display;
                    static ActivityOptions makeBasic() { return new ActivityOptions(); }
                    void setLaunchDisplayId(int d) { display=d; }
                    Object toBundle() { return display; }
                }
                static class AppLaunchTarget {}
                static class ShellAccess { static boolean ready=true; static boolean isReady() { return ready; } }
                static class TaskCommandQueue { static int calls; static void execute(Runnable r) { calls++; r.run(); } }
                static class TaskRepository {
                    static class TaskEntry { int taskId=41; }
                    interface ActionCallback { void onComplete(ActionResult result); }
                    static class ActionResult {
                        boolean success; String message;
                        ActionResult(boolean s,String m) { success=s; message=m; }
                    }
                    static Object loadNow(int displayId) { return new Object(); }
                }
                static class MagicDeskRuntime {
                    static List<TaskRepository.TaskEntry> getVisibleFreeformTasks(int id) { return List.of(new TaskRepository.TaskEntry()); }
                }
                static class DesktopTaskController {
                    static List<TaskRepository.TaskEntry> selectVisibleFreeformTasks(Object snapshot) { return List.of(); }
                }
                static class DesktopRuntimeBridge { static void syncTaskbarWithSnapshot(int displayId,Object snapshot) {} }
                static class WindowedAppLauncher {
                    interface TaskReadyCallback { void onTaskReady(); }
                    static int calls; static int[] preserved; static IOException failure;
                    static CompletableFuture<TaskRepository.ActionResult> ready=new CompletableFuture<>();
                    static class LaunchResult {
                        final CompletableFuture<TaskRepository.ActionResult> mReady=ready;
                """ + RuntimeSourceFixture.methods("WindowedAppLauncher", "whenReady") + "}\n"
                + """
                    static LaunchResult launchBuiltInWindow(Intent intent,AppLaunchTarget target,int display,
                            int[] tasks,TaskReadyCallback callback) throws IOException {
                        calls++; preserved=tasks;
                        if (failure!=null) throw failure;
                        return new LaunchResult();
                    }
                }
                """ + RuntimeSourceFixture.methods("BuiltInWindowLauncher", "launch", "taskIds", "complete");
    }
}
