package io.github.mekhontsev.magicdesk;

import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public final class OrdinaryActivityLaunchTest {
    @Test public void interactiveDisplayLaunchAndOwnTaskReuseNeedNoPrivilegedService() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Intent {}
                static class Context {
                    ActivityManager manager = new ActivityManager();
                    <T> T getSystemService(Class<T> type) { return type.cast(manager); }
                    int starts;
                    int destination; boolean denied;
                    PackageManager packages = new PackageManager();
                    PackageManager getPackageManager() { return packages; }
                    void startActivity(Intent intent, Object options) {
                        if (denied) throw new SecurityException("launch denied");
                        starts++; destination = ((ActivityOptions) options).display;
                    }
                }
                static class PackageManager {
                    static final String FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS = "secondary";
                    boolean secondary = true;
                    boolean hasSystemFeature(String feature) { return secondary; }
                }
                static class Activity extends Context {
                    boolean finishing, destroyed; Display display = new Display();
                    boolean isFinishing() { return finishing; }
                    boolean isDestroyed() { return destroyed; }
                    Display getDisplay() { return display; }
                }
                static class Display { int id; int getDisplayId() { return id; } }
                static class ActivityOptions {
                    int display;
                    static ActivityOptions makeBasic() { return new ActivityOptions(); }
                    void setLaunchDisplayId(int display) { this.display = display; }
                    Object toBundle() { return this; }
                }
                static class ActivityManager {
                    static boolean allowed = true;
                    boolean isActivityStartAllowedOnDisplay(Context c, int d, Intent i) { return allowed; }
                    List<AppTask> tasks = new ArrayList<>();
                    List<AppTask> getAppTasks() { return tasks; }
                    static class RecentTaskInfo { int taskId = 42, displayId; }
                    static class AppTask {
                        RecentTaskInfo info = new RecentTaskInfo(); int moves;
                        RecentTaskInfo getTaskInfo() { return info; }
                        void moveToFront() { moves++; }
                    }
                }
                static class BuiltInWindowRegistry {
                    static int display;
                    static boolean isTaskOnDisplay(int task, int target) { return display == target; }
                }
                static class DesktopRuntimeBridge {
                    static boolean active; static boolean hasWorkspaces() { return active; }
                }
                static class DesktopDisplayCatalog {
                    static int queries;
                    static void require(int display, String unique) throws IOException {
                        queries++;
                        if (display != 0 && display != 3 || unique != null && !unique.equals("display:" + display))
                            throw new IOException("stale display");
                    }
                }
                static class ShellAccess { static boolean ready; static boolean isReady() { return ready; } }
                static class AndroidLaunchSpec { enum Delivery { SHELL_INTENT, APP_PENDING_INTENT } }
                static int privilegedLaunches;
                static class OrdinaryActivityLaunch {
                    static void launch(Context c, Intent i, AndroidLaunchSpec.Delivery d, int display) {
                        privilegedLaunches++;
                    }
                }
                """ + RuntimeSourceFixture.nestedClass("InteractiveActivityLaunch", "OwnTaskResult")
                + RuntimeSourceFixture.methods("InteractiveActivityLaunch", "canLaunchLocally",
                        "requireDestination", "requirePublicLaunch", "launch", "showOwnTask")
                        .replace("android.content.pm.PackageManager", "PackageManager") + """
                public static void verify() throws Exception {
                    Activity activity = new Activity(); Intent intent = new Intent();
                    requireDestination(activity, 0, null);
                    for (var delivery : AndroidLaunchSpec.Delivery.values()) launch(activity, intent, delivery, 0);
                    check(activity.starts == 2 && privilegedLaunches == 0 && DesktopDisplayCatalog.queries == 0,
                            "phone tools touched privileged service");
                    check(showOwnTask(activity, 42, 0) == OwnTaskResult.MISSING, "missing window not distinguished");
                    var task = new ActivityManager.AppTask(); activity.manager.tasks.add(task);
                    check(showOwnTask(activity, 42, 0) == OwnTaskResult.SHOWN && task.moves == 1, "own task not reused");
                    for (int display : new int[] {-1, 3}) {
                        BuiltInWindowRegistry.display = display;
                        check(showOwnTask(activity, 42, 0) == OwnTaskResult.NEEDS_PLACEMENT && task.moves == 1,
                                "unknown or external task raised on wrong display");
                    }
                    requireDestination(activity, 0, "display:0");
                    requireDestination(activity, 3, "display:3");
                    for (String unique : new String[] {"phone", "stale"}) {
                        try { requireDestination(activity, 0, unique); throw new AssertionError("pin ignored"); }
                        catch (IOException expected) { }
                    }
                    check(!canLaunchLocally(new Context(), 0), "background got foreground privilege");
                    activity.finishing = true; check(!canLaunchLocally(activity, 0), "dead host allowed");
                    activity.finishing = false; activity.display.id = 3;
                    check(canLaunchLocally(activity, 0) && canLaunchLocally(activity, 3), "external host blocked");
                    BuiltInWindowRegistry.display = 3;
                    check(showOwnTask(activity, 42, 3) == OwnTaskResult.SHOWN && task.moves == 2,
                            "own external window required global task access");
                    launch(activity, intent, AndroidLaunchSpec.Delivery.SHELL_INTENT, 3);
                    check(activity.destination == 3 && activity.starts == 3 && privilegedLaunches == 0,
                            "external launch lost target or escalated");
                    activity.packages.secondary = false;
                    try { launch(activity, intent, AndroidLaunchSpec.Delivery.SHELL_INTENT, 3);
                        throw new AssertionError("unsupported display launch ignored"); } catch (IOException expected) { }
                    activity.packages.secondary = true; ActivityManager.allowed = false;
                    try { launch(activity, intent, AndroidLaunchSpec.Delivery.SHELL_INTENT, 3);
                        throw new AssertionError("Android denial ignored"); } catch (IOException expected) { }
                    ActivityManager.allowed = true; activity.denied = true;
                    try { launch(activity, intent, AndroidLaunchSpec.Delivery.SHELL_INTENT, 3);
                        throw new AssertionError("late denial ignored"); } catch (SecurityException expected) { }
                    check(privilegedLaunches == 0 && activity.starts == 3, "denied launch retried");
                    activity.denied = false; ShellAccess.ready = true;
                    check(!canLaunchLocally(activity, 3), "privileged external placement changed");
                    activity.display.id = 0; DesktopRuntimeBridge.active = true;
                    check(!canLaunchLocally(activity, 0), "desktop topology bypass");
                    launch(activity, intent, AndroidLaunchSpec.Delivery.SHELL_INTENT, 0);
                    check(privilegedLaunches == 1, "desktop boundary bypass");
                }
                """);
    }

    @Test public void ordinaryPresentationDoesNotRequireDesktop() {
        for (final var mode : new DesktopLaunchMode[] {DesktopLaunchMode.AUTO, DesktopLaunchMode.FULLSCREEN}) {
            for (final var instance : DesktopTaskInstancePolicy.values()) {
                OrdinaryActivityLaunch.requirePresentation(
                        DesktopLaunchPresentation.forMode(mode).withInstancePolicy(instance));
            }
        }
    }

    @Test public void desktopOnlyParametersAreNotSilentlyIgnored() {
        assertThrows(IllegalArgumentException.class, () -> OrdinaryActivityLaunch.requirePresentation(
                DesktopLaunchPresentation.forMode(DesktopLaunchMode.WINDOWED)));
        assertThrows(IllegalArgumentException.class, () -> OrdinaryActivityLaunch.requirePresentation(
                DesktopLaunchPresentation.forMode(DesktopLaunchMode.FULLSCREEN).withPreferredTask(12)));
    }

    @Test public void acceptanceDoesNotInventAnObservedTask() throws Exception {
        final var result = OrdinaryActivityLaunch.accepted(0,
                new JSONObject().put("requestId", "result-request").put("delivery", "pending-intent"));
        assertTrue(result.success);
        assertTrue(result.data.getBoolean("accepted"));
        assertFalse(result.data.getBoolean("taskObserved"));
        assertFalse(result.data.has("taskId"));
        assertFalse(result.data.has("reused"));
        assertEquals(0, result.data.getInt("displayId"));
        assertEquals("result-request", result.data.getString("requestId"));
        assertEquals("pending-intent", result.data.getString("delivery"));
        assertTrue(result.data.getString("nextAction").contains("ui.wait"));
    }

    @Test public void dispatchPreservesIdentityAndRevokesOnlyItsOwnToken() throws Exception {
        RuntimeSourceFixture.verify("""
                    static class ApplicationTaskPlacement {
                        static int preparations;
                        static void prepareIndependentLaunch(Context c, Intent i, int d) { preparations++; }
                    }
                    static class Context {}
                    static class Intent {}
                    static class PendingIntent { int cancellations; void cancel() { cancellations++; } }
                    static class AndroidLaunchSpec { enum Delivery { SHELL_INTENT, APP_PENDING_INTENT } }
                    static class AndroidPendingActivityLaunch {
                        static PendingIntent token; static Intent source;
                        static PendingIntent create(Context c, Intent intent) {
                            source=intent; return token=new PendingIntent();
                        }
                    }
                    static class ShellAccess {
                        static Intent source; static PendingIntent token; static int display;
                        static boolean fullscreen; static boolean fail;
                        static void sendActivityOnDisplay(PendingIntent p, int d) throws IOException {
                            token=p; display=d;
                            if(fail) throw new IOException("rejected");
                        }
                        static void launchActivityOnDisplay(Intent i, int d, boolean f) {
                            source=i; display=d; fullscreen=f;
                        }
                    }
                """ + RuntimeSourceFixture.methods("OrdinaryActivityLaunch", "launch") + """
                    public static void verify() throws Exception {
                        Context c=new Context(); Intent i=new Intent();
                        launch(c,i,AndroidLaunchSpec.Delivery.SHELL_INTENT,0);
                        if(ShellAccess.source!=i || !ShellAccess.fullscreen || ShellAccess.display!=0
                                || AndroidPendingActivityLaunch.token!=null) throw new AssertionError("direct path");
                        launch(c,i,AndroidLaunchSpec.Delivery.APP_PENDING_INTENT,7);
                        if(AndroidPendingActivityLaunch.source!=i || ShellAccess.display!=7
                                || ShellAccess.token!=AndroidPendingActivityLaunch.token
                                || ShellAccess.token.cancellations!=1) throw new AssertionError("app identity");
                        ShellAccess.fail=true;
                        try { launch(c,i,AndroidLaunchSpec.Delivery.APP_PENDING_INTENT,0);
                            throw new AssertionError("failure was hidden");
                        } catch(IOException expected) {}
                        if(ShellAccess.token.cancellations!=1) throw new AssertionError("failed token leaked");
                    }
                """);
    }
}
