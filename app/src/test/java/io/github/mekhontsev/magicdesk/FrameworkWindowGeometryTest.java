package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FrameworkWindowGeometryTest {
    @Test public void fullscreenRestoreUsesRetainedScaleInItsFirstTransaction() throws Exception {
        verify("""
                Task task = new Task(); task.mode = 1; task.scale = 1;
                Rect bounds = new Rect(1280, 677, 2560, 1355);
                var plan = FrameworkWindowGeometry.plan(task, bounds, .5576389f);
                plan.apply(new FrameworkWindowingApi(), new Object(), new Object());
                check(plan.scale == .5576389f && applied.right == 3576 && applied.bottom == 1893,
                        "restore used fullscreen scale instead of retained freeform geometry");
                observed = new FrameworkTaskSnapshot(); observed.windowScale = plan.scale;
                observed.bounds = plan.displayBounds();
                FrameworkWindowGeometry.finishPlacement(new Object(), 7, 42, bounds);
                check(resizes == 0, "known restore geometry required another visible resize");
                check(FrameworkWindowGeometry.capture(task).scale == 1, "fullscreen observation was scaled");
                """);
    }

    @Test public void retainedScaleCannotOverrideDisabledOrStandardGeometry() throws Exception {
        verify("""
                Task task = new Task(); task.mode = 1;
                Rect bounds = new Rect(10, 20, 810, 620);
                driver.scaled = false;
                check(FrameworkWindowGeometry.plan(task, bounds, .5f).scale == 1,
                        "standard path used retained vendor scale");
                driver.scaled = true; FrameworkWindowGeometry.initialize(false);
                check(FrameworkWindowGeometry.plan(task, bounds, .5f).scale == 1,
                        "disabled geometry used retained vendor scale");
                FrameworkWindowGeometry.initialize(true);
                try { FrameworkWindowGeometry.plan(task, bounds, Float.NaN);
                    throw new AssertionError("invalid retained scale accepted"); }
                catch (IllegalStateException expected) { }
                """);
    }

    @Test public void explicitPlacementAndRollbackShareOneCoordinateBoundary() throws Exception {
        verify("""
                Task task = new Task(); task.scale = .5f;
                task.bounds = new Rect(30, 40, 1230, 840);
                FrameworkWindowGeometry.Plan saved = FrameworkWindowGeometry.capture(task);
                check(saved.scale == .5f && saved.displayBounds().right == 630, "capture lost scale");
                FrameworkWindowGeometry.Plan plan = FrameworkWindowGeometry.plan(task, new Rect(10, 20, 810, 620));
                check(plan.logicalBounds.right == 1610 && plan.logicalBounds.bottom == 1220,
                        "requested display geometry not converted");
                Object transaction = new Object(), token = new Object();
                plan.apply(new FrameworkWindowingApi(), transaction, token);
                check(driver.transaction == transaction && driver.token == token && driver.scale == .5f,
                        "scale is not in bounds transaction");
                check(applied.right == 1610 && writes == 1, "logical bounds not published");
                task.scale = .8f;
                saved.apply(new FrameworkWindowingApi(), transaction, token);
                check(applied.right == 1230 && driver.scale == .5f, "rollback used destination scale");
                task.mode = 1;
                check(FrameworkWindowGeometry.capture(task).scale == 1, "fullscreen was scaled");
                task.mode = 5; task.scale = Float.NaN;
                try { FrameworkWindowGeometry.plan(task, new Rect(1,2,3,4)); throw new AssertionError("unknown scale accepted"); }
                catch (IllegalStateException expected) { }
                """);
    }

    @Test public void finalizesAtMostOnceAndPreservesExplicitFailure() throws Exception {
        verify("""
                observed = new FrameworkTaskSnapshot(); observed.windowScale = .5f;
                observed.bounds = new Rect(0,0,400,300);
                Rect requested = new Rect(0,0,800,600);
                FrameworkWindowGeometry.finishPlacement(new Object(), 7, 42, requested);
                check(barriers == 1 && samples == 1 && resizes == 1 && checks == 1, "unexpected placement loop");
                observed.bounds = requested;
                FrameworkWindowGeometry.finishPlacement(new Object(), 7, 42, requested);
                check(resizes == 1 && checks == 2, "matching geometry was rewritten");
                observed.windowScale = Float.NaN;
                try { FrameworkWindowGeometry.finishPlacement(new Object(),7,42,requested);
                    throw new AssertionError("unknown geometry accepted"); }
                catch (IllegalStateException expected) { }
                check(resizes == 1 && checks == 2, "unknown geometry submitted");
                observed.windowScale = .5f; observed.bounds = new Rect(0,0,400,300); reject = true;
                try { FrameworkWindowGeometry.finishPlacement(new Object(),7,42,requested);
                    throw new AssertionError("failed resize acknowledged"); }
                catch (IllegalStateException expected) { }
                check(resizes == 2 && checks == 2, "failed resize replayed");
                """);
    }

    @Test public void standardAndDisabledPathsDoNotAddQueriesOrTransactions() throws Exception {
        verify("""
                driver.scaled = false;
                FrameworkWindowGeometry.finishPlacement(new Object(),7,42,new Rect(0,0,800,600));
                check(barriers == 0 && samples == 0 && resizes == 0 && checks == 0, "identity path changed");
                driver.scaled = true; FrameworkWindowGeometry.initialize(false);
                FrameworkWindowGeometry.finishPlacement(new Object(),7,42,new Rect(0,0,800,600));
                Task task = new Task(); task.scale = .5f;
                var plan = FrameworkWindowGeometry.plan(task, new Rect(10,20,810,620));
                plan.apply(new FrameworkWindowingApi(),new Object(),new Object());
                check(plan.scale == 1 && applied.right == 810 && driver.transaction == null,
                        "disabled adapter changed native coordinates");
                check(barriers == 0 && samples == 0 && resizes == 0 && checks == 0, "disabled path queried");
                """);
    }

    private static void verify(String scenario) throws Exception {
        String source = Files.readString(Path.of(RuntimeSourceFixture.MAIN + "FrameworkWindowGeometry.java"));
        RuntimeSourceFixture.verify("""
                static class Rect {
                    int left, top, right, bottom;
                    Rect() { }
                    Rect(int l,int t,int r,int b) { left=l; top=t; right=r; bottom=b; }
                }
                static class Task { int mode=5; float scale=1; Rect bounds=new Rect(); }
                static class HiddenTaskApi {
                    static int getTaskWindowingMode(Object task) { return ((Task)task).mode; }
                    static Rect readBounds(Object task) { return ((Task)task).bounds; }
                }
                static class Driver {
                    boolean scaled=true; Object transaction, token; float scale;
                    boolean hasScaledWindows() { return scaled; }
                    float taskScale(Object task) { return ((Task)task).scale; }
                    void prepareTaskGeometry(Object tx,Class<?> type,Object target,float value) {
                        transaction=tx; token=target; scale=value;
                    }
                    String geometryDiagnostic() { return "fixture"; }
                }
                static final Driver driver = new Driver();
                static class PlatformDrivers {
                    static PlatformDrivers current() { return new PlatformDrivers(); }
                    Driver windowing() { return driver; }
                }
                static int writes, barriers, samples, resizes, checks;
                static Rect applied;
                static boolean reject;
                static class FrameworkWindowingApi {
                    Class<?> tokenClass() { return Object.class; }
                    void setBounds(Object tx,Object token,Rect bounds) { writes++; applied=bounds; }
                }
                static class FrameworkWindowCommitBarrier {
                    static void awaitSystemTransitions() { barriers++; }
                }
                static class FrameworkTaskSnapshot {
                    static final int WINDOWING_MODE_FREEFORM=5;
                    int windowingMode=5; float windowScale; Rect bounds;
                }
                static FrameworkTaskSnapshot observed;
                static class FrameworkTaskSnapshotSource {
                    static FrameworkTaskSnapshot findTask(Object service,int display,int task) { samples++; return observed; }
                }
                static class TaskDisplayAreaLaunchCommand {
                    static boolean satisfiesRequestedBounds(Rect a,Rect b) {
                        return a.left==b.left && a.top==b.top && a.right==b.right && a.bottom==b.bottom;
                    }
                    static void waitForTaskFreeformBounds(Object service,int display,int task,Rect bounds) { checks++; }
                }
                static class TaskWindowingCommand {
                    static void resizeBounds(Object service,int display,int[] tasks,Rect[] bounds) {
                        resizes++; if (reject) throw new IllegalStateException("firmware rejected geometry");
                        check(display==7 && tasks.length==1 && tasks[0]==42 && bounds.length==1, "target changed");
                    }
                }
                static class TaskWindowGeometry {
                """ + RuntimeSourceFixture.methods("TaskWindowGeometry", "validScale", "displayBounds",
                        "logicalBounds", "scaledSize", "edge") + "}\n"
                + source.substring(source.indexOf("final class FrameworkWindowGeometry"))
                        .replaceFirst("final class FrameworkWindowGeometry", "static final class FrameworkWindowGeometry")
                + "public static void verify() throws Exception { " + scenario + " }");
    }
}
