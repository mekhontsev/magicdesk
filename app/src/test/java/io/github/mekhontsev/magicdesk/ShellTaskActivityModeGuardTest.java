package io.github.mekhontsev.magicdesk;

import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

/** Runs the actual guard against deterministic task/launch events, without Android services. */
public final class ShellTaskActivityModeGuardTest {
    @Test
    public void restoresPreStartBoundsRatherThanFirstPostStartBounds() throws Exception {
        verify("""
                identify(1);
                observe(1, MAIN, ORIGINAL, 5, false);
                start(CHILD, 0);
                observe(1, CHILD, SHIFTED, 5, false);
                check(resizes == 1 && ORIGINAL.equals(restored), "lost pre-start bounds");
                check(modeChanges == 0, "bounds repair changed task mode");
                observe(1, CHILD, SHIFTED, 5, false);
                check(resizes == 1, "ordinary resize was undone after handoff");
                """);
    }

    @Test
    public void repeatedStartCanChangeBoundsWithoutChangingTopActivity() throws Exception {
        verify("""
                identify(1);
                observe(1, MAIN, ORIGINAL, 5, false);
                start(CHILD, 0);
                observe(1, CHILD, SHIFTED, 5, false);
                observe(1, CHILD, ORIGINAL, 5, false);
                start(CHILD, 0);
                observe(1, CHILD, SHIFTED, 5, false);
                check(resizes == 2 && ORIGINAL.equals(restored), "missed repeated start");
                check(modeChanges == 0, "repeated start reset mode");
                """);
    }

    @Test
    public void launchIdentitySuppliesBoundsBeforeFirstTaskSample() throws Exception {
        verify("""
                identify(1);
                start(CHILD, 0);
                observe(1, CHILD, SHIFTED, 5, false);
                check(resizes == 1 && ORIGINAL.equals(restored), "lost launch bounds");
                """);
    }

    @Test
    public void modeCorrectionAlsoUsesPreStartGeometry() throws Exception {
        verify("""
                identify(1);
                observe(1, MAIN, ORIGINAL, 5, false);
                start(CHILD, 0);
                observe(1, MAIN, SHIFTED, 5, false);
                observe(1, CHILD, SHIFTED, 1, false);
                check(modeChanges == 1 && ORIGINAL.equals(restored), "mode repair lost bounds");
                check(resizes == 0, "mode repair added another resize");
                """);
    }

    @Test
    public void ordinaryGeometryChangesAndIndependentLaunchesAreNotCorrected() throws Exception {
        verify("""
                identify(1);
                observe(1, MAIN, ORIGINAL, 5, false);
                observe(1, MAIN, SHIFTED, 5, false);
                for (int flag : new int[] {Intent.FLAG_ACTIVITY_NEW_TASK,
                        Intent.FLAG_ACTIVITY_NEW_DOCUMENT, Intent.FLAG_ACTIVITY_MULTIPLE_TASK}) {
                    start(CHILD, flag);
                    observe(1, CHILD, ORIGINAL, 5, false);
                    observe(1, MAIN, SHIFTED, 5, false);
                }
                check(resizes == 0 && modeChanges == 0, "corrected an unrelated action");
                """);
    }

    @Test
    public void unknownOrImmersiveClientDoesNotAuthorizeBoundsRepair() throws Exception {
        verify("""
                identify(1);
                observe(1, MAIN, ORIGINAL, 5, false);
                start(CHILD, 0);
                observe(1, CHILD, SHIFTED, 5, null);
                check(resizes == 0, "guessed missing immersive state");
                observe(1, CHILD, SHIFTED, 5, true);
                observe(1, CHILD, SHIFTED, 5, false);
                check(resizes == 0, "overrode an immersive request");
                """);
    }

    @Test
    public void ambiguousTargetsDoNotReceiveGeometryFromAnotherTask() throws Exception {
        verify("""
                identify(1);
                identify(2);
                guard.observeTasks(0, List.of(task(1, MAIN, ORIGINAL, 5, false),
                        task(2, MAIN, ORIGINAL, 5, false)));
                start(CHILD, 0);
                guard.observeTasks(0, List.of(task(1, CHILD, SHIFTED, 5, false),
                        task(2, CHILD, SHIFTED, 5, false)));
                check(resizes == 0 && modeChanges == 0, "guessed between two tasks");
                """);
    }

    @Test
    public void removedOrMovedTaskDoesNotDonateBoundsToANewRecord() throws Exception {
        verify("""
                identify(1);
                observe(1, MAIN, ORIGINAL, 5, false);
                start(CHILD, 0);
                guard.onTaskDisplayChanged(1, 8);
                observe(1, CHILD, SHIFTED, 5, false);
                check(resizes == 0, "reused a removed task's geometry");
                """);
    }

    @Test
    public void failedBoundsRepairDoesNotRetryDuringIdleObservation() throws Exception {
        verify("""
                identify(1);
                observe(1, MAIN, ORIGINAL, 5, false);
                start(CHILD, 0);
                failResize = true;
                observe(1, CHILD, SHIFTED, 5, false);
                observe(1, CHILD, SHIFTED, 5, false);
                check(resizes == 1, "failed resize started a retry loop");
                """);
    }

    @Test
    public void sessionDisableClearsPendingWorkAndDoesNotBlockExplicitFullscreen() throws Exception {
        verify("""
                identify(1);
                observe(1, MAIN, ORIGINAL, 5, false);
                start(CHILD, 0);
                guard.configure(0, false);
                observe(1, CHILD, SHIFTED, 1, false);
                check(guard.onExplicitFullscreenTaskIdentified(1, MAIN, 0),
                        "disabled guard rejected a valid fullscreen launch");
                guard.configure(0, true);
                observe(1, CHILD, SHIFTED, 5, false);
                check(resizes == 0 && modeChanges == 0, "retained old-session correction");
                """);
    }

    @Test
    public void unmatchedStartExpiresWithoutAWorker() throws Exception {
        verify("""
                identify(1);
                observe(1, MAIN, ORIGINAL, 5, false);
                start(CHILD, 0);
                SystemClock.now += 9000;
                observe(1, CHILD, SHIFTED, 5, false);
                check(resizes == 0 && modeChanges == 0, "retained stale launch evidence");
                """);
    }

    private static void verify(final String scenario) throws Exception {
        final String source = Files.readString(Path.of(RuntimeSourceFixture.MAIN
                + "ShellTaskActivityModeGuard.java"));
        final String guard = source.substring(source.indexOf("/**"))
                .replace("final class ShellTaskActivityModeGuard",
                        "static final class ShellTaskActivityModeGuard");
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static final ComponentName MAIN = new ComponentName("com.example", "Main");
                static final ComponentName CHILD = new ComponentName("com.example", "Child");
                static final Rect ORIGINAL = new Rect(25, 504, 937, 2001);
                static final Rect SHIFTED = new Rect(304, 490, 1216, 2506);
                static final ShellTaskActivityModeGuard guard =
                        new ShellTaskActivityModeGuard(new Object(), null, () -> false);
                static int resizes, modeChanges;
                static Rect restored;
                static boolean failResize;
                static void identify(int id) {
                    guard.onTaskIdentified(id, MAIN, 0, ORIGINAL, 5);
                }
                static void start(ComponentName target, int flags) {
                    check(guard.onActivityStarting(new Intent(target, flags),
                            target.getPackageName()), "blocked activity launch");
                }
                static FrameworkTaskSnapshot task(int id, ComponentName top, Rect bounds,
                        int mode, Boolean immersive) {
                    return new FrameworkTaskSnapshot(id, top, bounds, mode, immersive);
                }
                static void observe(int id, ComponentName top, Rect bounds,
                        int mode, Boolean immersive) {
                    guard.observeTasks(0, List.of(task(id, top, bounds, mode, immersive)));
                }
                public static void verify() {
                    guard.configure(0, true);
                """ + scenario + "}\n" + guard + """
                static final class Rect {
                    int left, top, right, bottom;
                    Rect() {}
                    Rect(int l, int t, int r, int b) {
                        left = l; top = t; right = r; bottom = b;
                    }
                    Rect(Rect r) { set(r); }
                    void set(Rect r) {
                        left = r.left; top = r.top; right = r.right; bottom = r.bottom;
                    }
                    boolean isEmpty() { return left >= right || top >= bottom; }
                    @Override public boolean equals(Object value) {
                        return value instanceof Rect r && left == r.left && top == r.top
                                && right == r.right && bottom == r.bottom;
                    }
                    @Override public int hashCode() { return Objects.hash(left, top, right, bottom); }
                }
                record ComponentName(String pkg, String name) {
                    String getPackageName() { return pkg; }
                    String flattenToShortString() { return pkg + "/." + name; }
                }
                record Intent(ComponentName component, int flags) {
                    static final int FLAG_ACTIVITY_NEW_TASK = 0x10000000;
                    static final int FLAG_ACTIVITY_NEW_DOCUMENT = 0x00080000;
                    static final int FLAG_ACTIVITY_MULTIPLE_TASK = 0x08000000;
                    ComponentName getComponent() { return component; }
                    String getPackage() { return component.getPackageName(); }
                    int getFlags() { return flags; }
                }
                static final class SystemClock {
                    static long now;
                    static long uptimeMillis() { return now; }
                }
                static final class Display { static final int INVALID_DISPLAY = -1; }
                static final class Log {
                    static void d(String tag, String message) {}
                    static void i(String tag, String message) {}
                    static void w(String tag, String message) {}
                }
                static final class FrameworkTaskSnapshot {
                    final int taskId, windowingMode;
                    final ComponentName rootComponent = MAIN;
                    final ComponentName topComponent;
                    final Rect bounds;
                    final boolean visible = true;
                    final Boolean immersive;
                    FrameworkTaskSnapshot(int id, ComponentName top, Rect rect,
                            int mode, Boolean request) {
                        taskId = id; topComponent = top; bounds = new Rect(rect);
                        windowingMode = mode; immersive = request;
                    }
                    Boolean requestingImmersive() { return immersive; }
                }
                static final class DesktopInfrastructureTasks {
                    static boolean isComponent(ComponentName name) { return false; }
                }
                static final class LaunchActivityIdentity {
                    boolean matches(ComponentName name) { return false; }
                    boolean matchesPackage(ComponentName name) { return false; }
                    boolean matchesPackage(String name) { return false; }
                }
                interface ShellTaskLauncher {
                    interface Listener {
                        void onTaskLaunchStarting(LaunchActivityIdentity id, int mode);
                        void onTaskLaunchFinished(LaunchActivityIdentity id, int mode);
                        void onTaskIdentified(int id, ComponentName name, int display,
                                Rect bounds, int mode);
                    }
                }
                interface ShellActivityStartController {
                    interface Listener { boolean onActivityStarting(Intent intent, String pkg); }
                }
                static final class HiddenTaskApi {
                    static void resizeTaskBounds(Object service, int display, int task, Rect bounds)
                            throws ReflectiveOperationException {
                        resizes++;
                        if (failResize) throw new ReflectiveOperationException("resize unavailable");
                        restored = new Rect(bounds);
                    }
                }
                static final class ShellPreparedTaskTransition {
                    static void applyFreeform(Object service, int display, int task, Rect bounds) {
                        modeChanges++; restored = new Rect(bounds);
                    }
                }
                static final class TaskFullscreenTransitionCommand {
                    static void applyFullscreen(int display, int task, boolean caption) {
                        modeChanges++;
                    }
                }
                """, "TaskActivityModeState", "PackageNameValidator");
    }
}
