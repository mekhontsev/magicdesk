package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class RunningWindowPresentationTest {
    @Test public void resolvesUnlistedToolsAndEachHostedWindowIndependently() throws Exception {
        RuntimeSourceFixture.verify("""
                static class TaskRepository {
                    record TaskEntry(int taskId, int userId, String packageName, String component) { }
                }
                record AppIdentity(String packageName) { }
                static class Profile {
                    AppIdentity application(TaskRepository.TaskEntry task) { return new AppIdentity(task.packageName()); }
                }
                record AppLaunchTarget(String packageName, String component) {
                    static AppLaunchTarget packageDefault(String pkg) { return new AppLaunchTarget(pkg, ""); }
                }
                static class BuiltInDesktopAppCatalog {
                    static class Entry { AppLaunchTarget launchTarget; Entry(AppLaunchTarget t) { launchTarget = t; } }
                    static Entry find(TaskRepository.TaskEntry task) {
                        return task.component().isEmpty() ? null : new Entry(new AppLaunchTarget(task.packageName(), task.component()));
                    }
                }
                static class AppItem {
                    static final int FULLSCREEN_REASON_NONE = 0;
                    String label; AppLaunchTarget target; Object icon;
                    AppItem(Profile p, String label, String pkg, boolean universal, int reason, Object icon, AppLaunchTarget target) {
                        this.label = label; this.target = target; this.icon = icon;
                    }
                }
                static class PackageManager { Object getDefaultActivityIcon() { return "default"; } }
                static class BuiltInWindowRegistry {
                    static AppItem present(Object c, AppItem app, TaskRepository.TaskEntry task) {
                        return new AppItem(null, "window-" + task.taskId(), app.target.packageName(), false, 0, app.icon, app.target);
                    }
                }
                Profile mProfile = new Profile();
                PackageManager mPackageManager = new PackageManager();
                Object mContext;
                int loads;
                boolean missing;
                boolean owns(TaskRepository.TaskEntry task) { return task != null && task.userId() == 0; }
                AppItem findOrLoad(List<AppItem> apps, AppIdentity app, AppLaunchTarget target, boolean universal) {
                    loads++;
                    return missing ? null : new AppItem(mProfile, "base", app.packageName(), universal, 0, "icon", target);
                }
                public static void verify() {
                    Fixture f = new Fixture();
                    check(f.findOrLoadTask(List.of(), new TaskRepository.TaskEntry(1, 10, "app", ""), false) == null,
                            "foreign profile resolved");
                    check(f.loads == 0, "queried metadata for another user");
                    for (String component : List.of("Console", "X11", "Wayland", "Viewer", "Settings", "")) {
                        AppItem a = f.findOrLoadTask(List.of(), new TaskRepository.TaskEntry(1, 0, "app", component), false);
                        AppItem b = f.findOrLoadTask(List.of(), new TaskRepository.TaskEntry(2, 0, "app", component), false);
                        check(a.target.component().equals(component), "tool resolved as package launcher");
                        check(a.label.equals("window-1") && b.label.equals("window-2"), "window presentations were merged");
                    }
                    f.missing = true;
                    AppItem a = f.findOrLoadTask(List.of(), new TaskRepository.TaskEntry(3, 0, "app", "Wayland"), false);
                    check(a != null && a.icon.equals("default") && a.label.equals("window-3"), "missing package hid live task");
                }
                """ + RuntimeSourceFixture.methods("LauncherAppRepository", "findOrLoadTask")
                        .replace("task.packageName", "task.packageName()"));
    }

    @Test public void refreshTargetsTheVisibleSourceAndExistingWindowsCannotRequestNewInstances() throws Exception {
        RuntimeSourceFixture.verify("""
                static final int MENU_RUNNING = 2;
                int mMode;
                static class Host { int requests; void onSectionShown(int section) { requests++; } }
                static class Catalog { int requests; void refreshApplications() { requests++; } }
                Host mHost = new Host(); Catalog mCatalog = new Catalog();
                public static void verify() {
                    Fixture f = new Fixture();
                    f.mMode = MENU_RUNNING; f.refreshEntries();
                    check(f.mHost.requests == 1 && f.mCatalog.requests == 0, "Running refreshed launcher catalog");
                    f.mMode = 1; f.refreshEntries();
                    check(f.mHost.requests == 1 && f.mCatalog.requests == 1, "Applications refreshed tasks");
                }
                """ + RuntimeSourceFixture.methods("StartMenuContent", "refreshEntries"));
        RuntimeSourceFixture.verify("""
                enum DesktopTaskInstancePolicy { CREATE_NEW, REUSE_EXISTING }
                static class DesktopLaunchPresentation {
                    DesktopTaskInstancePolicy policy;
                    static DesktopLaunchPresentation forMode(int mode) { return new DesktopLaunchPresentation(); }
                    DesktopLaunchPresentation withInstancePolicy(DesktopTaskInstancePolicy policy) { this.policy = policy; return this; }
                }
                static class Mode { int windowMode; }
                static class CheckBox {
                    boolean enabled = true, checked = true;
                    void setEnabled(boolean value) { enabled = value; }
                    boolean isEnabled() { return enabled; }
                    boolean isChecked() { return checked; }
                }
                Mode mMode = new Mode(); CheckBox mNew = new CheckBox();
                public static void verify() {
                    Fixture f = new Fixture();
                    f.setExistingWindow(true);
                    check(!f.mNew.enabled && f.presentation().policy == DesktopTaskInstancePolicy.REUSE_EXISTING, "Running creates new window");
                    f.setExistingWindow(false);
                    check(f.mNew.enabled && f.presentation().policy == DesktopTaskInstancePolicy.CREATE_NEW, "launch preference lost");
                }
                """ + RuntimeSourceFixture.methods("StartLaunchControls", "setExistingWindow", "presentation"));
    }

    @Test public void contextActionsKeepTaskAndRecipeIdentityAndRenderingDoesNotLoadPackages() throws Exception {
        final String menu = RuntimeSourceFixture.methods("FullscreenStartController", "appContext");
        assertTrue(menu.contains("StartMenuEntry entry"));
        assertTrue(menu.contains("launch(entry, target, selected)"));
        assertFalse(menu.contains("StartMenuEntry.app("));
        final String bind = RuntimeSourceFixture.methods("StartMenuContent", "bindContextMenu");
        assertTrue(bind.contains("mHost.appContext(view, entry, removeRecent)"));
        final String render = RuntimeSourceFixture.methods("FullscreenStartController", "runningEntries");
        assertFalse(render.contains("findOrLoad"));
        assertFalse(render.contains("LauncherAppRepository"));
        assertTrue(RuntimeSourceFixture.methods("StartMenuContent", "renderBody")
                .contains("mMode != MENU_RUNNING &&"));
        final String load = RuntimeSourceFixture.methods("FullscreenStartController", "loadRunning");
        assertTrue(load.indexOf("TaskCommandQueue.execute") < load.indexOf("findOrLoadTask"));
        for (String file : new String[]{"DesktopShellActivity", "IndependentApplicationsDialog"}) {
            assertTrue(file, java.nio.file.Files.readString(java.nio.file.Path.of(RuntimeSourceFixture.MAIN + file + ".java"))
                    .contains("findOrLoadTask("));
        }
    }
}
