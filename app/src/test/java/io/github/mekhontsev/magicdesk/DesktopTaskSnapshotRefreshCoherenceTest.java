package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopTaskSnapshotRefreshCoherenceTest {
    @Test public void pendingCommandOwnsHostInputUntilItsAcknowledgement() throws Exception {
        verify("""
                MagicDeskRuntime.pending = 10;
                f.refresh();
                check(f.mActivity.focusable, "old application focus cancelled pending desktop focus");
                var host = new TaskRepository.TaskEntry("independent-host");
                MagicDeskRuntime.observed = new TaskRepository.Snapshot(List.of(host), true, "");
                MagicDeskRuntime.pending = 44872;
                f.refresh();
                check(!f.mActivity.focusable, "old host focus cancelled pending application focus");
                MagicDeskRuntime.pending = -1;
                f.refresh();
                check(f.mActivity.focusable, "completed command masked observed focus");
                """);
    }

    @Test
    public void focusedDesktopAcceptsInputBelowUnfocusedApplications() throws Exception {
        verify("""
                for (String hostMode : List.of("home", "independent-host")) {
                    for (String appMode : List.of("freeform", "fullscreen", "2")) {
                        var application = new TaskRepository.TaskEntry(appMode);
                        application.active = false;
                        var host = new TaskRepository.TaskEntry(hostMode);
                        MagicDeskRuntime.observed = new TaskRepository.Snapshot(
                                List.of(application, host), true, "");
                        f.refresh();
                        check(f.mActivity.focusable,
                                "focused " + hostMode + " lost input below " + appMode);
                    }
                }
                """);
    }

    @Test
    public void focusedApplicationRetainsInputBelowUnfocusedDesktop() throws Exception {
        verify("""
                for (String hostMode : List.of("home", "independent-host")) {
                    var host = new TaskRepository.TaskEntry(hostMode);
                    host.active = false;
                    var application = new TaskRepository.TaskEntry("freeform");
                    MagicDeskRuntime.observed = new TaskRepository.Snapshot(
                            List.of(host, application), true, "");
                    f.refresh();
                    check(!f.mActivity.focusable,
                            "unfocused " + hostMode + " overrode application focus");
                }
                """);
    }

    @Test
    public void independentAndChromeFocusCannotBeReplacedByManagedScene() throws Exception {
        verify("""
                MagicDeskRuntime.excludeTasks = true;
                for (String mode : List.of("freeform", "fullscreen", "2", "chrome")) {
                    var host = new TaskRepository.TaskEntry("independent-host");
                    host.active = false;
                    MagicDeskRuntime.observed = new TaskRepository.Snapshot(List.of(
                            host, new TaskRepository.TaskEntry(mode)), true, "");
                    f.refresh();
                    check(!f.mActivity.focusable, "desktop took focus from " + mode);
                    check(f.mSnapshot.tasks.isEmpty(), "focus acquired independent ownership");
                }
                """);
    }

    @Test
    public void emptyFocusAllowsAndroidToSelectDesktop() throws Exception {
        verify("""
                var application = new TaskRepository.TaskEntry("freeform");
                application.active = false;
                MagicDeskRuntime.observed = new TaskRepository.Snapshot(
                        List.of(application), true, "");
                f.refresh();
                check(f.mActivity.focusable, "missing focus disabled the desktop fallback");
                """);
    }

    @Test
    public void pictureInPictureOverHomeDoesNotConcealTaskbar() throws Exception {
        verify("""
                MagicDeskRuntime.excludeTasks = true;
                MagicDeskRuntime.observed = new TaskRepository.Snapshot(List.of(
                        new TaskRepository.TaskEntry("2"),
                        new TaskRepository.TaskEntry("home")), true, "");
                f.mActivity.visible = false;
                f.refresh();
                check(f.mActivity.visible, "PiP concealed the HOME taskbar");
                check(!f.mActivity.focusable, "PiP presentation gave HOME keyboard focus");
                check(f.mSnapshot.tasks.isEmpty(), "presentation acquired independent tasks");
                check(TaskRepository.loads == 0, "presentation issued a second query");
                """);
    }

    @Test
    public void pictureInPictureFollowsTheSceneBelowIt() throws Exception {
        verify("""
                for (String mode : List.of("freeform", "fullscreen")) {
                    MagicDeskRuntime.observed = new TaskRepository.Snapshot(List.of(
                            new TaskRepository.TaskEntry("2"),
                            new TaskRepository.TaskEntry(mode),
                            new TaskRepository.TaskEntry("home")), true, "");
                    f.refresh();
                    check(f.mActivity.visible == mode.equals("freeform"),
                            "PiP overrode the underlying " + mode + " scene");
                }
                """);
    }

    @Test
    public void repeatedSnapshotsDoNotRewriteHistory() throws Exception {
        verify("""
                f.refresh(); f.refresh(); f.refresh();
                check(RecentApplications.records == 1, "unchanged focus rewrote history on every snapshot");
                var previous = MagicDeskRuntime.observed;
                MagicDeskRuntime.observed = new TaskRepository.Snapshot(List.of(), true, "");
                f.refresh();
                MagicDeskRuntime.observed = previous;
                f.refresh();
                check(RecentApplications.records == 2, "returning to a task did not update history");
                """);
    }

    @Test
    public void activeRefreshCannotPublishUnobservedHandoffFullscreen() throws Exception {
        verify("""
                f.refresh();
                check(f.mActivity.visible && f.mActivity.hidden == 0,
                        "raw handoff snapshot hid taskbar before observer correction");
                check(f.mSnapshot == MagicDeskRuntime.observed, "UI bypassed controller observation");
                check(TaskRepository.loads == 0, "active UI issued an independent task query");
                """);
    }

    @Test
    public void observedFullscreenStillConcealsTaskbar() throws Exception {
        verify("""
                MagicDeskRuntime.observed = TaskRepository.raw;
                f.refresh();
                check(!f.mActivity.visible && f.mActivity.hidden == 1,
                        "observed fullscreen no longer conceals taskbar");
                check(TaskRepository.loads == 0, "fullscreen policy used another query");
                """);
    }

    @Test
    public void foreignFreeformKeepsChromeWithoutEnteringManagedTaskList() throws Exception {
        verify("""
                MagicDeskRuntime.excludeTasks = true;
                f.refresh();
                check(f.mActivity.visible, "foreign freeform concealed taskbar");
                check(f.mSnapshot.tasks.isEmpty(), "chrome policy acquired foreign task ownership");
                check(TaskRepository.loads == 0, "chrome policy issued another query");
                MagicDeskRuntime.observed = TaskRepository.raw;
                f.refresh();
                check(!f.mActivity.visible, "foreign fullscreen did not conceal taskbar");
                check(f.mSnapshot.tasks.isEmpty(), "fullscreen presentation acquired independent task");
                """);
    }

    @Test
    public void unknownActiveObservationDoesNotFallBackToRawOrEmptySuccess() throws Exception {
        verify("""
                MagicDeskRuntime.observed = null;
                f.refresh();
                check(f.mActivity.visible && f.mActivity.hidden == 0, "unknown observation changed visibility");
                check(!f.mSnapshot.available, "unknown observation became available emptiness");
                check(TaskRepository.loads == 0, "unknown active observation used raw fallback");
                """);
    }

    @Test
    public void inactiveRefreshRetainsFreshQuery() throws Exception {
        verify("""
                DesktopRuntimeBridge.activeDisplay = -1;
                f.refresh();
                check(TaskRepository.loads == 1 && f.mSnapshot == TaskRepository.raw,
                        "inactive refresh stopped using its fresh snapshot");
                check(!f.mActivity.visible, "inactive fullscreen policy changed");
                """);
    }

    @Test
    public void pendingRawReplyCannotOverwriteNewActiveObservation() throws Exception {
        verify("""
                DesktopRuntimeBridge.activeDisplay = -1; TaskRepository.defer = true;
                f.refresh();
                DesktopRuntimeBridge.activeDisplay = 66;
                TaskRepository.pending.accept(TaskRepository.raw);
                check(f.mActivity.visible && f.mActivity.hidden == 0,
                        "pre-session raw reply overwrote active observation");
                check(f.mSnapshot == MagicDeskRuntime.observed, "late reply bypassed provider");
                """);
    }

    @Test
    public void releasedOrMovedHostIgnoresPendingReply() throws Exception {
        verify("""
                DesktopRuntimeBridge.activeDisplay = -1; TaskRepository.defer = true;
                f.refresh(); f.release();
                TaskRepository.pending.accept(TaskRepository.raw);
                check(f.mActivity.hidden == 0 && f.mActivity.updates == 0, "released host accepted stale reply");
                f.refresh(); f.mActivity.displayId = 67;
                TaskRepository.pending.accept(TaskRepository.raw);
                check(f.mActivity.hidden == 0 && f.mActivity.updates == 0, "moved host accepted stale display reply");
                """);
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static class android { static class view { static class Display { static final int DEFAULT_DISPLAY = 0; } } }
                static class TaskRepository {
                    static class TaskEntry {
                        boolean active = true, visible = true; final String mode; final int taskId;
                        TaskEntry(String mode) {
                            this.mode = mode;
                            taskId = mode.equals("home") || mode.equals("independent-host") ? 10 : 44872;
                        }
                        boolean isFreeform() { return "freeform".equals(mode); }
                        boolean isFullscreen() { return "fullscreen".equals(mode); }
                    }
                    static class Snapshot {
                        final List<TaskEntry> tasks; final boolean available; final String error;
                        Snapshot(List<TaskEntry> tasks, boolean available, String error) {
                            this.tasks = tasks; this.available = available; this.error = error;
                        }
                    }
                    static int loads; static boolean defer; static java.util.function.Consumer<Snapshot> pending;
                    static Snapshot raw = new Snapshot(List.of(new TaskEntry("fullscreen")), true, "");
                    static void load(int display, java.util.function.Consumer<Snapshot> callback) {
                        loads++; if (defer) pending = callback; else callback.accept(raw);
                    }
                }
                static class DesktopSessionSnapshot {
                    int activeWorkspaceDisplayId() { return DesktopRuntimeBridge.activeDisplay; }
                }
                static class DesktopRuntimeBridge {
                    static int activeDisplay = 66;
                    static DesktopSessionSnapshot getSessionSnapshot(int displayId) { return new DesktopSessionSnapshot(); }
                }
                static class MagicDeskRuntime {
                    static boolean excludeTasks;
                    static int pending = -1;
                    static int taskInputFocusTarget(int display, int observed) { return pending >= 0 ? pending : observed; }
                    static TaskRepository.Snapshot observed = new TaskRepository.Snapshot(
                            List.of(new TaskRepository.TaskEntry("freeform")), true, "");
                    static TaskRepository.Snapshot observedTaskSnapshot(int display) {
                        check(display == 66, "provider read escaped host display scope"); return observed;
                    }
                    static TaskRepository.Snapshot selectDesktopTaskSnapshot(int display, TaskRepository.Snapshot snapshot) {
                        return excludeTasks ? new TaskRepository.Snapshot(Collections.emptyList(), true, "") : snapshot;
                    }
                }
                static class Activity {
                    int displayId = 66, hidden, updates; boolean visible = true, unavailable, focusable;
                    int getTaskId() { return 10; }
                    int getCurrentDisplayId() { return displayId; }
                    boolean isActivityUnavailable() { return unavailable; }
                    void runOnUiThread(Runnable action) { action.run(); }
                    void updateDesktopControls() { updates++; }
                    TaskRepository.Snapshot shellTasks;
                    void publishShellTasks(TaskRepository.Snapshot snapshot) { shellTasks = snapshot; }
                    AppProfile appProfile() { return new AppProfile(); }
                    Object getLauncherApps() { return null; }
                    void renderTaskbarPins(Object apps) {}
                    boolean isTaskbarVisible() { return visible; }
                    void setTaskbarVisible(boolean value) { if (visible && !value) hidden++; visible = value; }
                    void setDesktopWindowFocusable(boolean value) { focusable = value; }
                }
                static class DesktopTaskController { static boolean isDesktopHostTask(TaskRepository.TaskEntry task) { return "home".equals(task.mode) || "independent-host".equals(task.mode); } }
                static class DesktopInfrastructureTasks { static boolean isTask(TaskRepository.TaskEntry task) { return false; } }
                static class RecentApplications { static int records; static void recordTask(Activity activity, TaskRepository.TaskEntry task, Object apps) { records++; } }
                record AppReference(String key) {}
                static class AppProfile { AppReference reference(Object task) { return new AppReference("0|fixture"); } }
                static boolean isTaskbarTask(TaskRepository.TaskEntry task) { return true; }
                final Activity mActivity = new Activity();
                final DesktopTaskbarDialogHold mSystemDialogHold = new DesktopTaskbarDialogHold();
                TaskRepository.Snapshot mSnapshot = new TaskRepository.Snapshot(Collections.emptyList(), false, "not loaded");
                int mRefreshGeneration, mRecentTaskId = -1;
                public static void verify() { Fixture f = new Fixture();
                """ + scenario + "}\n" + RuntimeSourceFixture.methods("DesktopTaskSnapshotController",
                "refresh", "applyRefreshSnapshot", "release", "sync", "selectDesktopTaskSnapshot",
                "findActiveTask")
                + RuntimeSourceFixture.nestedClass("DesktopWorkspaceScene", "DesktopWorkspaceScene")
                + "static class DesktopTaskbarVisibilityPolicy {"
                + RuntimeSourceFixture.methods("DesktopTaskbarVisibilityPolicy", "isVisible") + "}",
                "DesktopTaskbarDialogHold");
    }
}
