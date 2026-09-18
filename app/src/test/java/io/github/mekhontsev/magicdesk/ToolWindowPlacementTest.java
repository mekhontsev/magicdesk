package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class ToolWindowPlacementTest {
    @Test public void replacementReadsFinalLocalBoundsWithoutAnotherTaskQuery() throws Exception {
        RuntimeSourceFixture.verify("""
            enum DesktopLaunchMode { FULLSCREEN, WINDOWED }
            enum DesktopTaskInstancePolicy { CREATE_NEW }
            record DesktopLaunchPresentation(DesktopLaunchMode mode, RelativeWindowBounds bounds,
                    DesktopTaskInstancePolicy instancePolicy, int preferredTaskId) { }
            record RelativeWindowBounds(int value) {
                static RelativeWindowBounds from(Integer bounds, Integer area) {
                    return bounds == null || area == null ? null : new RelativeWindowBounds(bounds);
                }
            }
            static class ToolLaunchTarget { int displayId = 7; }
            record WindowPlacement(ToolLaunchTarget target, DesktopLaunchPresentation presentation) { }
            record Display(int id) { int getDisplayId() { return id; } }
            static class Activity {
                Display display = new Display(7);
                Integer bounds = 594;
                int reads;
                Display getDisplay() { return display; }
                Activity getWindowManager() { return this; }
                Activity getCurrentWindowMetrics() { reads++; return this; }
                Integer getBounds() { return bounds; }
            }
            static class DesktopRuntimeBridge {
                static Integer area = 1920;
                static Integer getDesktopWorkAreaBounds(int display) { check(display == 7, "other display"); return area; }
            }
            public static void verify() {
                var activity = new Activity();
                var prior = new DesktopLaunchPresentation(DesktopLaunchMode.WINDOWED,
                        new RelativeWindowBounds(568), DesktopTaskInstancePolicy.CREATE_NEW, -1);
                var placement = new WindowPlacement(new ToolLaunchTarget(), prior);
                var replacement = replacementPresentation(activity, placement);
                check(replacement.bounds.value == 594 && prior.bounds.value == 568 && activity.reads == 1,
                        "position-only movement was replaced by the old snapshot");
                activity.bounds = 570;
                check(replacementPresentation(activity, placement).bounds.value == 570, "each close reads latest local position");
                var fullscreen = new DesktopLaunchPresentation(DesktopLaunchMode.FULLSCREEN, null,
                        DesktopTaskInstancePolicy.CREATE_NEW, -1);
                check(replacementPresentation(activity, new WindowPlacement(new ToolLaunchTarget(), fullscreen)) == fullscreen
                        && activity.reads == 2, "fullscreen closure changed placement or read geometry");
                DesktopRuntimeBridge.area = null;
                try { replacementPresentation(activity, placement); throw new AssertionError("missing work area accepted"); }
                catch (IllegalStateException expected) { }
                activity.display = new Display(9);
                try { replacementPresentation(activity, placement); throw new AssertionError("cross-display stale placement accepted"); }
                catch (IllegalStateException expected) { }
            }
            """ + RuntimeSourceFixture.methods("ToolApplications", "replacementPresentation")
                    .replace("android.app.Activity", "Activity"));
    }

    @Test public void replacementCapturesOwnershipModeAndBoundsWithoutAcquiringDesktop() throws Exception {
        RuntimeSourceFixture.verify("""
            enum DesktopLaunchMode { FULLSCREEN, WINDOWED }
            enum DesktopTaskInstancePolicy { CREATE_NEW }
            record DesktopLaunchPresentation(DesktopLaunchMode mode, RelativeWindowBounds bounds,
                    DesktopTaskInstancePolicy instancePolicy, int preferredTaskId) { }
            record RelativeWindowBounds(int value) {
                static RelativeWindowBounds from(int bounds, int area) {
                    check(area == 100, "work area"); return new RelativeWindowBounds(bounds);
                }
            }
            record ToolLaunchTarget(String placement, int display) {
                static ToolLaunchTarget resolve(String placement, int display, Set<Integer> desktops) {
                    return new ToolLaunchTarget(placement, display);
                }
            }
            record WindowPlacement(ToolLaunchTarget target, String uniqueId, DesktopLaunchPresentation presentation) { }
            static class DesktopRuntimeBridge {
                static boolean active;
                static boolean hasWorkspace(int id) { return active; }
                static Set<Integer> workspaceDisplayIds() { return active ? Set.of(7) : Set.of(); }
            }
            static class TaskRepository {
                static int reads;
                static final Task task = new Task();
                static class Task { int taskId = 11, bounds = 45; boolean freeform = true; boolean isFreeform() { return freeform; } }
                static class Snapshot { boolean available = true; String error = "unknown"; List<Task> tasks = List.of(task); }
                static Snapshot loadNow(int id) { reads++; return new Snapshot(); }
            }
            static class ApplicationTaskPlacement {
                static String owner = "desktop";
                static String ownership(Object task, Object snapshot) { return owner; }
            }
            static class FloatingWindowController { static int getWorkAreaBounds(int id) { return 100; } }
            static class DesktopDisplayCatalog {
                static class Display { String uniqueId = "stable"; }
                static Display require(int id, Object uniqueId) { return new Display(); }
            }
            public static void verify() throws Exception {
                var ordinary = windowPlacement(7, 11);
                check(TaskRepository.reads == 0 && ordinary.target.placement.equals("display"), "independent tool queried Desktop");
                check(ordinary.presentation.mode == DesktopLaunchMode.FULLSCREEN && ordinary.presentation.bounds == null,
                        "independent replacement must remain ordinary");
                DesktopRuntimeBridge.active = true;
                var managed = windowPlacement(7, 11);
                check(managed.target.placement.equals("desktop") && managed.uniqueId.equals("stable"), "destination ownership");
                check(managed.presentation.mode == DesktopLaunchMode.WINDOWED && managed.presentation.bounds.value == 45
                        && managed.presentation.instancePolicy == DesktopTaskInstancePolicy.CREATE_NEW, "window geometry and new task");
                TaskRepository.task.freeform = false;
                check(windowPlacement(7, 11).presentation.mode == DesktopLaunchMode.FULLSCREEN, "fullscreen replacement");
                ApplicationTaskPlacement.owner = "display";
                check(windowPlacement(7, 11).target.placement.equals("display"), "workspace presence is not ownership");
                ApplicationTaskPlacement.owner = "unknown";
                try { windowPlacement(7, 11); throw new AssertionError("unknown ownership accepted"); }
                catch (IOException expected) { }
                ApplicationTaskPlacement.owner = "desktop";
                try { windowPlacement(7, 12); throw new AssertionError("missing task accepted"); }
                catch (IOException expected) { }
            }
            """ + RuntimeSourceFixture.methods("ToolApplications", "windowPlacement"));
    }
}
