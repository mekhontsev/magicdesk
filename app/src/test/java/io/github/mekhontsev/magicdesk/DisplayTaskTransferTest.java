package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** Exercise shared placement and ownership without an Android organizer. */
public final class DisplayTaskTransferTest {
    @Test public void reusedIntentKeepsRequestedModeAndBoundsThroughOwnershipPreparation() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Context { Object getPackageManager() { return null; } }
                static class Intent {
                    static final int FLAG_ACTIVITY_MULTIPLE_TASK = 1;
                    boolean explicit = true; int flags;
                    Object getComponent() { return explicit ? this : null; }
                    int getFlags() { return flags; }
                }
                enum DesktopLaunchMode { AUTO, WINDOWED, FULLSCREEN }
                static class DesktopLaunchPresentation { DesktopLaunchMode mode = DesktopLaunchMode.AUTO; Object bounds; }
                static class AppProfile {
                    int userId; static AppProfile current(Context c) { return new AppProfile(); }
                }
                static class LaunchActivityIdentity {
                    static Object resolve(int u, Object pm, Object component) { return component; }
                }
                static class TaskRepository {
                    static int reads;
                    static class TaskEntry { int displayId = 7; boolean freeform; boolean isFreeform() { return freeform; } }
                    static class Snapshot { boolean available = true; String error = "unavailable"; }
                    static Snapshot loadAllNow() { reads++; return new Snapshot(); }
                }
                static TaskRepository.TaskEntry task = new TaskRepository.TaskEntry();
                static TaskRepository.TaskEntry selectExisting(Object identity, TaskRepository.Snapshot s, int display) { return task; }
                static boolean managed;
                static boolean isManaged(TaskRepository.TaskEntry task) { return managed; }
                static class ToolLaunchTarget {
                    static Object resolve(String mode, int d, Set<Integer> desktops) {
                        check(mode.equals("display") && d == 7 && desktops.isEmpty(), "ordinary launch claimed Desktop"); return null;
                    }
                }
                static int preparations;
                static DesktopLaunchPresentation received;
                static void prepare(TaskRepository.TaskEntry task, Object target, String unique, DesktopLaunchPresentation p) {
                    preparations++; received = p;
                }
                """ + RuntimeSourceFixture.methods("ApplicationTaskPlacement", "prepareIndependentLaunch") + """
                public static void verify() throws Exception {
                    Context c = new Context(); Intent i = new Intent(); var p = new DesktopLaunchPresentation();
                    prepareIndependentLaunch(c,i,7,p);
                    check(preparations == 0, "ordinary same-mode launch moved task");
                    p.mode = DesktopLaunchMode.WINDOWED;
                    prepareIndependentLaunch(c,i,7,p);
                    check(preparations == 1 && received == p, "windowed request normalized to fullscreen");
                    task.freeform = true;
                    prepareIndependentLaunch(c,i,7,p);
                    check(preparations == 1, "matching ordinary freeform was moved");
                    p.bounds = new Object();
                    prepareIndependentLaunch(c,i,7,p);
                    check(preparations == 2 && received.bounds == p.bounds, "reused task ignored requested bounds");
                    p.bounds = null; managed = true;
                    prepareIndependentLaunch(c,i,7,p);
                    check(preparations == 3 && received == p, "managed task was not released in requested mode");
                    managed = false; task.displayId = 8;
                    prepareIndependentLaunch(c,i,7,p);
                    check(preparations == 4, "other-display task was not prepared");
                    int reads = TaskRepository.reads;
                    i.explicit = false; prepareIndependentLaunch(c,i,7,p);
                    i.explicit = true; i.flags = 1; prepareIndependentLaunch(c,i,7,p);
                    check(TaskRepository.reads == reads && preparations == 4, "resolver or new instance changed an existing task");
                }
                """);
    }

    @Test public void transferUsesExplicitOwnershipAndRejectsStaleTasks() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static Set<Integer> desktops = new HashSet<>();
                static Set<Integer> owned = new HashSet<>();
                static List<String> events = new ArrayList<>();
                static boolean transition, ownershipReady = true, profile = true;
                static TaskRepository.TaskEntry live = new TaskRepository.TaskEntry();
                static class Rect {
                    Rect() {} Rect(Rect b) {}
                }
                static class RelativeWindowBounds {}
                enum DesktopLaunchMode { AUTO, WINDOWED, FULLSCREEN }
                static class DesktopLaunchPresentation {
                    DesktopLaunchMode mode = DesktopLaunchMode.AUTO;
                    RelativeWindowBounds bounds;
                }
                static class TaskRepository {
                    static class TaskEntry {
                        int taskId = 5, displayId, userId;
                        String packageName = "app";
                        boolean freeform;
                        Rect bounds = new Rect();
                        boolean isFreeform() { return freeform; }
                        boolean hasBounds() { return true; }
                    }
                    static class Snapshot {
                        boolean available = true;
                        String error = "unavailable";
                        List<TaskEntry> tasks = List.of(live);
                    }
                    static Snapshot loadNow(int d) { return new Snapshot(); }
                    static Snapshot loadAllNow() { return new Snapshot(); }
                    static boolean isTransferable(TaskEntry t) { return profile && t.userId == 0; }
                }
                static class DesktopRuntimeBridge {
                    static boolean hasWorkspace(int d) { return desktops.contains(d); }
                    static Set<Integer> workspaceDisplayIds() { return desktops; }
                }
                static class MagicDeskRuntime {
                    static TaskRepository.Snapshot selectDesktopTaskSnapshot(int d, TaskRepository.Snapshot all) {
                        TaskRepository.Snapshot s = new TaskRepository.Snapshot();
                        s.available = ownershipReady;
                        s.tasks = owned.contains(live.taskId) ? List.of(live) : List.of();
                        return s;
                    }
                    static boolean attachFullscreenTask(int d, int t, int dpi) {
                        events.add("attach-fullscreen"); owned.add(t); live.freeform = false; return true;
                    }
                    static boolean attachWindowedTask(int d, int t, Rect b, int dpi) {
                        events.add("attach-windowed"); owned.add(t); live.freeform = true; return true;
                    }
                }
                static class DesktopOperations {
                    static boolean isSessionTransitionInProgress() { return transition; }
                }
                static class DesktopDisplayCatalog { static void require(int d, String id) throws IOException {
                    if ("stale".equals(id)) { throw new IOException("stale display"); }
                } }
                static class AppIdentity {}
                static class AppProfile {
                    static AppProfile current(Object c) { return new AppProfile(); }
                    AppIdentity application(TaskRepository.TaskEntry t) { return profile ? new AppIdentity() : null; }
                }
                static class MagicDeskApplication { static Object applicationContext() { return null; } }
                static class DesktopTaskPresentationPolicy {
                    static int resolveDensityDpi(AppIdentity a, int d) { return 240; }
                }
                static class FloatingWindowController {
                    static Rect getWindowBounds(int d, Object b) { return new Rect(); }
                }
                static class OrdinaryActivityLaunch {
                    static Rect windowBounds(int d, DesktopLaunchPresentation p) {
                        return p.mode == DesktopLaunchMode.WINDOWED ? new Rect() : null;
                    }
                }
                static class ShellAccess {
                    static void releaseDesktopTasks(int d, int[] tasks) {
                        check(d == live.displayId, "released another display");
                        events.add("release"); owned.remove(live.taskId); live.freeform = false;
                    }
                    static void moveOrdinaryTask(TaskRepository.TaskEntry task, int d, Rect bounds) {
                        events.add("ordinary"); live.displayId = d; live.freeform = bounds != null;
                    }
                }
                static class DesktopTaskTransfer {
                    static String moveFreeform(int t, int s, int d, Rect b, int dpi) {
                        events.add("move-windowed"); live.displayId = d; live.freeform = true; return "";
                    }
                    static String moveFullscreen(int t, int s, int d, int dpi) {
                        events.add("move-fullscreen"); live.displayId = d; live.freeform = false; return "";
                    }
                }
                static void reset(int source, boolean managed, Integer... workspaces) {
                    desktops = new HashSet<>(Arrays.asList(workspaces));
                    owned.clear(); events.clear(); transition = false; ownershipReady = true; profile = true;
                    live = new TaskRepository.TaskEntry(); live.displayId = source;
                    if (managed) { owned.add(live.taskId); }
                }
                static void place(int display, String placement, DesktopLaunchMode mode) throws IOException {
                    DesktopLaunchPresentation p = new DesktopLaunchPresentation(); p.mode = mode;
                    prepare(live, ToolLaunchTarget.resolve(placement, display, desktops), null, p);
                }
                public static void verify() throws Exception {
                    reset(0, false);
                    place(7, "display", DesktopLaunchMode.AUTO);
                    check(events.equals(List.of("ordinary")), "ordinary placement required Desktop");
                    reset(0, false);
                    place(7, "display", DesktopLaunchMode.WINDOWED);
                    check(events.equals(List.of("ordinary")) && live.freeform && owned.isEmpty(),
                            "independent freeform acquired Desktop or lost mode");
                    reset(7, true, 7);
                    place(8, "display", DesktopLaunchMode.WINDOWED);
                    check(events.equals(List.of("release", "ordinary")) && live.freeform && owned.isEmpty(),
                            "independent freeform bypassed previous owner");
                    reset(0, false, 7);
                    place(7, "desktop", DesktopLaunchMode.WINDOWED);
                    check(events.equals(List.of("move-windowed", "attach-windowed")), "destination never claimed task");
                    reset(7, true, 7);
                    place(8, "display", DesktopLaunchMode.AUTO);
                    check(events.equals(List.of("release", "ordinary")), "departure bypassed owner");
                    reset(7, true, 7);
                    place(7, "display", DesktopLaunchMode.FULLSCREEN);
                    check(events.equals(List.of("release", "ordinary")), "same-display release ignored");
                    reset(7, false, 7);
                    place(7, "desktop", DesktopLaunchMode.FULLSCREEN);
                    check(events.equals(List.of("attach-fullscreen")), "independent fullscreen never claimed");
                    reset(7, true, 7);
                    place(7, "desktop", DesktopLaunchMode.AUTO);
                    check(events.isEmpty(), "managed selection changed topology");
                    place(7, "desktop", DesktopLaunchMode.WINDOWED);
                    check(events.equals(List.of("attach-windowed")), "fullscreen restore bypassed owner");
                    reset(7, false, 7);
                    place(7, "display", DesktopLaunchMode.AUTO);
                    check(events.equals(List.of("ordinary")) && owned.isEmpty(), "independent app became managed");
                    reset(7, true, 7, 8);
                    place(8, "desktop", DesktopLaunchMode.FULLSCREEN);
                    check(events.equals(List.of("release", "move-fullscreen", "attach-fullscreen")), "workspace transfer mixed owners");
                    reset(7, true, 7);
                    ownershipReady = false;
                    try { place(7, "display", DesktopLaunchMode.AUTO); throw new AssertionError("unknown ownership guessed"); }
                    catch (IOException expected) { check(events.isEmpty(), "mutated unknown ownership"); }
                    reset(0, false);
                    transition = true;
                    try { place(7, "display", DesktopLaunchMode.AUTO); throw new AssertionError("transfer during close"); }
                    catch (IOException expected) { check(events.isEmpty(), "mutated during close"); }
                    transition = false; profile = false;
                    try { place(7, "display", DesktopLaunchMode.AUTO); throw new AssertionError("profile escaped"); }
                    catch (IOException expected) { check(events.isEmpty(), "mutated another profile"); }
                    reset(0, false);
                    TaskRepository.TaskEntry stale = new TaskRepository.TaskEntry(); stale.displayId = 3;
                    try { prepare(stale, ToolLaunchTarget.resolve("display", 7, desktops), null,
                            new DesktopLaunchPresentation()); throw new AssertionError("stale task"); }
                    catch (IOException expected) { check(events.isEmpty(), "stale source mutated"); }
                    try { prepare(live, ToolLaunchTarget.resolve("display", 7, desktops), "stale",
                            new DesktopLaunchPresentation()); throw new AssertionError("stale display"); }
                    catch (IOException expected) { check(events.isEmpty(), "stale display mutated"); }
                }
                """ + RuntimeSourceFixture.methods("ApplicationTaskPlacement", "prepare", "requireLive", "isManaged"),
                "ToolLaunchTarget");
    }
}
