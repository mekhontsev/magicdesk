package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class ToolWindowPlacementTest {
    @Test public void siblingBoundsFollowOwnershipNotSourceWindowMode() throws Exception {
        String method = RuntimeSourceFixture.methods("ToolApplications", "openSibling")
                .replace("android.app.Activity", "Activity");
        RuntimeSourceFixture.verify("""
            record Intent() { }
            record DesktopLaunchPresentation() { }
            static class Activity {
                Display getDisplay() { return new Display(); } int getTaskId() { return 12; }
                boolean isDestroyed() { return false; } boolean isFinishing() { return false; }
                void runOnUiThread(Runnable work) { work.run(); }
            }
            static class Display { int getDisplayId() { return 7; } }
            static class Target { boolean desktop=true; }
            record WindowPlacement(Target target,String uniqueId) { }
            static final Target target=new Target();
            static WindowPlacement windowPlacement(int display,int task) throws java.io.IOException {
                check(display==7 && task==12,"source identity"); return new WindowPlacement(target,"stable");
            }
            static class TaskCommandQueue { static void execute(Runnable work) { work.run(); } }
            static class BuiltInWindowLauncher { interface Callback { void onComplete(Throwable error); } }
            static DesktopLaunchPresentation actual;
            static void open(Activity source,Intent intent,Target target,String identity,
                    DesktopLaunchPresentation bounds,BuiltInWindowLauncher.Callback callback) { actual=bounds; callback.onComplete(null); }
            public static void verify() {
                var bounds=new DesktopLaunchPresentation();
                var source=new Activity(); var intent=new Intent();
                BuiltInWindowLauncher.Callback done=error -> { check(error==null,"launch error"); };
                openSibling(source,intent,bounds,done);
                check(actual==bounds,"managed source, including fullscreen, must retain explicit dialog bounds");
                target.desktop=false;
                openSibling(source,intent,bounds,done);
                check(actual==null,"independent source must retain ordinary Android placement");
            }
            """ + method);
    }

    @Test public void hostedSizeLimitsDoNotRequireAParent() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
            enum DesktopLaunchMode { WINDOWED }
            enum DesktopTaskInstancePolicy { CREATE_NEW }
            record DesktopLaunchPresentation(DesktopLaunchMode mode, RelativeWindowBounds bounds,
                    DesktopTaskInstancePolicy instancePolicy, int preferredTaskId) { }
            record RelativeWindowBounds(Rect bounds) {
                static RelativeWindowBounds from(Rect bounds, Rect work) { return new RelativeWindowBounds(bounds); }
            }
            static class Rect {
                int left, top, right, bottom;
                Rect(int l, int t, int r, int b) { left=l; top=t; right=r; bottom=b; }
                int width() { return right-left; } int height() { return bottom-top; }
                int centerX() { return (left+right)/2; } int centerY() { return (top+bottom)/2; }
                boolean isEmpty() { return width()<=0 || height()<=0; }
            }
            static class Insets {
                static final Rect NONE = new Rect(0,0,0,0);
                static Rect max(Rect a, Rect b) {
                    return new Rect(Math.max(a.left,b.left),Math.max(a.top,b.top),
                            Math.max(a.right,b.right),Math.max(a.bottom,b.bottom));
                }
            }
            record Display(int id) { int getDisplayId() { return id; } }
            static class WindowInsets {
                static class Type { static int systemBars() { return 1; } static int captionBar() { return 2; } }
                final int top;
                WindowInsets(int top) { this.top=top; }
                Rect getInsets(int types) { return new Rect(0,top,0,0); }
            }
            static class Activity {
                boolean multi = true;
                WindowInsets metrics = new WindowInsets(0), published = new WindowInsets(40);
                boolean isInMultiWindowMode() { return multi; }
                Display getDisplay() { return new Display(7); }
                Activity getWindowManager() { return this; }
                Activity getCurrentWindowMetrics() { return this; }
                Rect getBounds() { return new Rect(100,100,900,700); }
                WindowInsets getWindowInsets() { return metrics; }
                Activity getWindow() { return this; } Activity getDecorView() { return this; }
                WindowInsets getRootWindowInsets() { return published; }
            }
            static class DesktopRuntimeBridge {
                static Rect work = new Rect(0,0,1920,1016);
                static Rect getDesktopWorkAreaBounds(int display) { check(display==7,"wrong display"); return work; }
            }
            static io.github.mekhontsev.magicdesk.hosted.HostedWindowLayout layout(
                    long parent, int width, int height, int minW, int minH, int maxW, int maxH) {
                return new io.github.mekhontsev.magicdesk.hosted.HostedWindowLayout(parent, width, height,
                        new io.github.mekhontsev.magicdesk.hosted.HostedWindowConstraints(minW,minH,maxW,maxH));
            }
            static void bounds(DesktopLaunchPresentation result, int l, int t, int r, int b) {
                check(result!=null,"missing presentation");
                Rect actual=result.bounds.bounds;
                check(actual.left==l && actual.top==t && actual.right==r && actual.bottom==b,
                        "unexpected bounds: "+actual.left+","+actual.top+","+actual.right+","+actual.bottom);
                check(result.mode==DesktopLaunchMode.WINDOWED && result.instancePolicy==DesktopTaskInstancePolicy.CREATE_NEW,
                        "native window must get its own windowed host");
            }
            public static void verify() {
                var source=new Activity();
                var fixed=layout(0,420,398,420,398,420,398);
                bounds(hostedWindowPresentation(source,fixed,1),750,289,1170,727);
                source.metrics=new WindowInsets(40);
                bounds(hostedWindowPresentation(source,fixed,1),750,289,1170,727);
                source.published=null;
                bounds(hostedWindowPresentation(source,fixed,1),750,289,1170,727);
                source.metrics=new WindowInsets(0); source.published=new WindowInsets(40);
                bounds(hostedWindowPresentation(source,fixed,1.5f),645,190,1275,827);
                bounds(hostedWindowPresentation(source,layout(5,420,398,420,398,420,398),1),290,181,710,619);
                bounds(hostedWindowPresentation(source,layout(5,800,600,0,0,0,0),1),100,80,900,720);
                bounds(hostedWindowPresentation(source,layout(0,900,800,0,0,420,398),1),750,289,1170,727);
                bounds(hostedWindowPresentation(source,layout(0,900,600,0,0,420,0),1),750,188,1170,828);
                check(hostedWindowPresentation(source,layout(0,1920,1040,576,618,0,0),1)==null,
                        "ordinary resizable application must retain default/saved placement");
                check(hostedWindowPresentation(source,layout(0,0,0,0,0,420,398),1)==null,"unknown dimensions");
                source.multi=false;
                bounds(hostedWindowPresentation(source,fixed,1),750,309,1170,707);
                source.multi=true;
                DesktopRuntimeBridge.work=new Rect(200,100,500,400);
                bounds(hostedWindowPresentation(source,fixed,1),200,100,500,400);
                DesktopRuntimeBridge.work=null;
                check(hostedWindowPresentation(source,fixed,1)==null,"no workspace");
                DesktopRuntimeBridge.work=new Rect(0,0,0,0);
                check(hostedWindowPresentation(source,fixed,1)==null,"empty workspace");
            }
            """ + RuntimeSourceFixture.methods("ToolApplications", "hostedWindowPresentation")
                    .replace("android.app.Activity", "Activity")
                    .replace("android.view.WindowInsets", "WindowInsets")
                    .replace("android.graphics.Insets", "Insets")
                    .replace("android.graphics.Rect", "Rect"),
                java.nio.file.Path.of("../hosted-runtime/src/main/java/io/github/mekhontsev/magicdesk/hosted/HostedWindowLayout.java").toAbsolutePath().toString(),
                java.nio.file.Path.of("../hosted-runtime/src/main/java/io/github/mekhontsev/magicdesk/hosted/HostedWindowConstraints.java").toAbsolutePath().toString());
    }

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
