package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Exercise the shared production dispatcher without an Android organizer. */
public final class DisplayTaskTransferTest {
    @Test public void transferUsesDestinationOwnershipAndRejectsStaleTasks() throws Exception {
        RuntimeSourceFixture.verify("""
                static class TaskEntry { int taskId = 5, displayId = 0; }
                static class RelativeWindowBounds { }
                static class AppIdentity { }
                static class ActionResult { boolean success; String message;
                    ActionResult(boolean s, String m) { success=s; message=m; } }
                interface ActionCallback { void onComplete(ActionResult r); }
                static TaskEntry live = new TaskEntry();
                static String route = "";
                static boolean success, transition, profile = true;
                static int desktop = -1;
                static class Snapshot { boolean available = true; String error = "";
                    List<TaskEntry> tasks = List.of(live); }
                static boolean isTransferable(TaskEntry t) { return t != null; }
                static Snapshot loadAllNow() throws IOException { return new Snapshot(); }
                static TaskEntry findMatchingTask(List<TaskEntry> tasks, TaskEntry t) {
                    return tasks.get(0).taskId == t.taskId ? tasks.get(0) : null;
                }
                static void complete(ActionCallback c, boolean s, String m) {
                    c.onComplete(new ActionResult(s,m));
                }
                static String usefulMessage(Exception e) { return e.getMessage(); }
                static class TaskCommandQueue { static void execute(Runnable r) { r.run(); } }
                static class DesktopOperations {
                    static boolean isSessionTransitionInProgress() { return transition; }
                }
                static class DesktopDisplayCatalog { static void require(int d,String id) { } }
                static class DesktopDisplayDrivers {
                    static boolean hasActiveWorkspace(int id) { return desktop == id; }
                }
                static class MagicDeskApplication { static Object applicationContext() { return null; } }
                static class AppProfile {
                    static AppProfile current(Object c) { return new AppProfile(); }
                    AppIdentity application(TaskEntry t) { return profile ? new AppIdentity() : null; }
                }
                static class MagicDeskRuntime {
                    static void focusDesktopTask(int d,int t,ActionCallback c) {
                        route="focus"; complete(c,true,route);
                    }
                }
                static class DesktopTaskTransfer {
                    static String moveFreeform(int t,int s,int d,Object b,int dpi) {
                        return route="freeform";
                    }
                    static String moveFullscreen(int t,int s,int d,int dpi) {
                        check(dpi == -1,"retained Desktop density"); return route="fullscreen";
                    }
                }
                static class FloatingWindowController {
                    static Object getWindowBounds(int d,Object b) { return b; }
                }
                static class DesktopTaskPresentationPolicy {
                    static int resolveDensityDpi(AppIdentity a,int d) { return 240; }
                }
                static class DesktopTaskDensity { static int INHERIT=-1; }
                static class ShellAccess {
                    static void moveOrdinaryTask(TaskEntry t,int d) { route="ordinary"; }
                }
                static void run(int source,int destination,int owner) {
                    route=""; success=false; desktop=owner;
                    live.displayId=source;
                    TaskEntry request=new TaskEntry(); request.displayId=source;
                    moveTaskToDisplay(request,destination,null,null,r -> success=r.success);
                }
                public static void verify() {
                    run(0,7,-1); check(success && route.equals("ordinary"),"ordinary launch required Desktop");
                    run(7,0,-1); check(success && route.equals("ordinary"),"ordinary return required Desktop");
                    run(0,7,7); check(success && route.equals("freeform"),"Desktop destination bypassed owner");
                    run(7,8,7); check(success && route.equals("fullscreen"),"Desktop departure bypassed owner");
                    run(7,7,7); check(success && route.equals("focus"),"same-display selection changed mode");
                    run(0,0,-1); check(success && route.equals("focus"),"ordinary activation transferred task");
                    transition=true; run(0,7,-1); check(!success && route.isEmpty(),"transfer during close");
                    transition=false; profile=false; run(0,7,-1);
                    check(!success && route.isEmpty(),"cross-profile transfer"); profile=true;
                    route=""; live.displayId=8;
                    moveTaskToDisplay(new TaskEntry(),7,null,null,r -> success=r.success);
                    check(!success && route.isEmpty(),"stale source accepted");
                }
                """ + RuntimeSourceFixture.methods("TaskRepository", "moveTaskToDisplay"));
    }
}
