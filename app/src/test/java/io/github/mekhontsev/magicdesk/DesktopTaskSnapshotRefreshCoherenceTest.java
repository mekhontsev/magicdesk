package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopTaskSnapshotRefreshCoherenceTest {
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
                        boolean active = true, visible = true; final String mode; final int taskId = 44872;
                        TaskEntry(String mode) { this.mode = mode; }
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
                    static DesktopSessionSnapshot getSessionSnapshot() { return new DesktopSessionSnapshot(); }
                }
                static class MagicDeskRuntime {
                    static TaskRepository.Snapshot observed = new TaskRepository.Snapshot(
                            List.of(new TaskRepository.TaskEntry("freeform")), true, "");
                    static TaskRepository.Snapshot observedTaskSnapshot(int display) {
                        check(display == 66, "provider read escaped host display scope"); return observed;
                    }
                    static TaskRepository.Snapshot selectDesktopTaskSnapshot(int display, TaskRepository.Snapshot snapshot) { return snapshot; }
                }
                static class Activity {
                    int displayId = 66, hidden, updates; boolean visible = true, unavailable;
                    int getCurrentDisplayId() { return displayId; }
                    boolean isActivityUnavailable() { return unavailable; }
                    void runOnUiThread(Runnable action) { action.run(); }
                    void updateDesktopControls() { updates++; }
                    AppProfile appProfile() { return new AppProfile(); }
                    Object getLauncherApps() { return null; }
                    void renderTaskbarPins(Object apps) {}
                    boolean isTaskbarVisible() { return visible; }
                    void setTaskbarVisible(boolean value) { if (visible && !value) hidden++; visible = value; }
                    void setTaskbarAvailable(boolean value) {}
                    void setDesktopWindowFocusable(boolean value) {}
                }
                static class DesktopTaskController { static boolean isDesktopHostTask(TaskRepository.TaskEntry task) { return false; } }
                static class DesktopInfrastructureTasks { static boolean isTask(TaskRepository.TaskEntry task) { return false; } }
                static class DesktopManagedTaskPolicy { static boolean isControllableApplicationTask(TaskRepository.TaskEntry task) { return true; } }
                static class DesktopPreferences { static void recordRecentApp(Activity activity, AppReference key) {} }
                record AppReference(String key) {}
                static class AppProfile { AppReference reference(Object task) { return new AppReference("0|fixture"); } }
                static boolean isTaskbarTask(TaskRepository.TaskEntry task) { return true; }
                final Activity mActivity = new Activity();
                final DesktopTaskbarDialogHold mSystemDialogHold = new DesktopTaskbarDialogHold();
                TaskRepository.Snapshot mSnapshot = new TaskRepository.Snapshot(Collections.emptyList(), false, "not loaded");
                int mRefreshGeneration;
                public static void verify() { Fixture f = new Fixture();
                """ + scenario + "}\n" + RuntimeSourceFixture.methods("DesktopTaskSnapshotController",
                "refresh", "applyRefreshSnapshot", "release", "sync", "selectDesktopTaskSnapshot",
                "findActiveTask", "isDesktopChromeAvailable", "isDesktopHostForeground",
                "hasVisibleFreeformTask", "hasVisibleFullscreenTask"),
                "DesktopTaskbarVisibilityPolicy", "DesktopTaskbarDialogHold");
    }
}
