package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class NativeWindowBoundsReconciliationTest {
    @Test public void independentMaximizeAxesKeepOneRestoreRectangle() throws Exception {
        verify("""
                f.sample(ordinary);
                Dispatch d = new Dispatch(f);
                var horizontal = io.github.mekhontsev.magicdesk.hosted.HostedMaximization.HORIZONTAL;
                var vertical = io.github.mekhontsev.magicdesk.hosted.HostedMaximization.VERTICAL;
                for (var axes : List.of(horizontal, io.github.mekhontsev.magicdesk.hosted.HostedMaximization.BOTH, vertical)) {
                    d.setMaximized(f.task, axes, null);
                    Rect expected = WindowMaximization.target(axes, ordinary, work);
                    check(f.requests.get(f.requests.size()-1).equals(expected), "independent axis geometry");
                    f.sample(expected); f.complete(true); f.sample(expected);
                    check(f.state().windowRestoreBounds().equals(ordinary), "partial maximization retains restore");
                }
                d.setMaximized(f.task, io.github.mekhontsev.magicdesk.hosted.HostedMaximization.NONE, null);
                check(f.requests.get(f.requests.size()-1).equals(ordinary), "axis restoration returns original rectangle");
                """);
    }
    @Test public void clientMaximizeIsIdempotentAndRestoresOrdinaryBounds() throws Exception {
        verify("""
                f.sample(ordinary);
                Dispatch d = new Dispatch(f);
                d.setMaximized(f.task, io.github.mekhontsev.magicdesk.hosted.HostedMaximization.BOTH, null);
                check(f.requests.get(0).equals(work), "maximize did not use shared work area");
                f.sample(work); f.complete(true); f.sample(work);
                d.setMaximized(f.task, io.github.mekhontsev.magicdesk.hosted.HostedMaximization.BOTH, null);
                check(f.requests.size() == 1, "repeated maximize toggled or resized");
                check(f.state().windowRestoreBounds().equals(ordinary), "maximize overwrote ordinary geometry");
                d.setMaximized(f.task, io.github.mekhontsev.magicdesk.hosted.HostedMaximization.NONE, null);
                check(f.requests.get(1).equals(ordinary), "client restore lost original geometry");
                f.complete(true); f.sample(ordinary);
                d.setMaximized(f.task, io.github.mekhontsev.magicdesk.hosted.HostedMaximization.NONE, null);
                check(f.requests.size() == 2, "repeated restore toggled or resized");
                """);
    }
    @Test
    public void cornerHalfCornerPreservesOriginalBoundsUntilRestoreOrManualMove() throws Exception {
        verify("""
                f.sample(ordinary);
                Dispatch d = new Dispatch(f);
                for (int row : new int[]{0, -1, 0, 1}) {
                    d.snap(f.task, true, row);
                    check(f.state().manualImmersiveOverride, "snap did not retain windowed preference");
                    Rect target = f.getSnappedBounds(true, row);
                    f.sample(target);
                    f.complete(true);
                    f.sample(target);
                    check(f.state().windowRestoreBounds().equals(ordinary), "snap chain lost original bounds");
                    check(f.state().lastWindowBounds().equals(ordinary), "snap became ordinary geometry");
                }
                d.applyRestoreShortcut(f.task);
                check(f.state().manualImmersiveOverride, "restore did not retain windowed preference");
                check(f.requests.get(4).equals(ordinary), "restore did not use pre-snap geometry");
                f.complete(true);
                f.sample(ordinary);
                check(f.state().arrangedWindowBounds() == null, "restore left stale arrangement");
                d.snap(f.task, false, -1);
                f.complete(true);
                f.sample(f.getSnappedBounds(false, -1));
                Rect moved = new Rect(600, 85, 1500, 560);
                f.sample(moved);
                check(f.state().windowRestoreBounds() == null, "quarter snap pinned manual move");
                check(f.state().arrangedWindowBounds() == null, "manual move retained arrangement");
                check(f.requests.size() == 6, "manual move generated a correction");
                """);
    }

    @Test
    public void rapidFullscreenSnapSequenceUsesOneExitAndLatestRectangle() throws Exception {
        verify("""
                f.sample(ordinary);
                f.task.fullscreen = true;
                f.state().setFullscreenRestoreBounds(ordinary);
                Dispatch d = new Dispatch(f);
                d.snap(f.task, true, 0);
                d.snap(f.task, true, -1);
                // Android may publish freeform before its exit callback completes.
                f.task.fullscreen = false;
                d.snap(f.task, true, 0);
                d.snap(f.task, true, 1);
                check(d.exits.size() == 1, "snap chain submitted multiple fullscreen exits");
                check(f.requests.isEmpty(), "resize raced fullscreen exit");
                d.exitCallbacks.remove().onComplete(new TaskRepository.ActionResult(true, "ok"));
                Rect target = f.getSnappedBounds(true, 1);
                check(f.requests.size() == 1 && f.requests.get(0).equals(target), "latest corner was lost");
                check(f.state().pendingSnapBounds() == null, "pending snap survived completion");
                check(!f.state().isFullscreenTransition(), "fullscreen exit remained pending");
                f.complete(true);
                f.sample(target);
                check(f.state().windowRestoreBounds().equals(ordinary), "fullscreen snap lost restore bounds");
                d.applyRestoreShortcut(f.task);
                check(f.requests.get(1).equals(ordinary), "fullscreen snap did not restore original window");
                """);
    }

    @Test
    public void failedOrRemovedFullscreenSnapCannotResizeAnotherTask() throws Exception {
        verify("""
                f.sample(ordinary);
                f.task.fullscreen = true;
                f.state().setFullscreenRestoreBounds(ordinary);
                Dispatch d = new Dispatch(f);
                d.snap(f.task, false, 0);
                d.snap(f.task, false, -1);
                d.exitCallbacks.remove().onComplete(new TaskRepository.ActionResult(false, "failed"));
                check(f.requests.isEmpty(), "failed exit submitted a resize");
                check(f.state().pendingSnapBounds() == null, "failed exit left pending snap");
                check(!f.state().isFullscreenTransition(), "failed exit blocks retry");
                check(f.state().fullscreenRestoreBounds().equals(ordinary), "failure erased fullscreen restore");
                d.snap(f.task, false, 1);
                check(d.exits.size() == 2, "retry was ignored");
                f.mTaskStates.states.clear();
                DesktopTaskRuntimeState replacement = f.state();
                d.exitCallbacks.remove().onComplete(new TaskRepository.ActionResult(true, "late"));
                check(f.requests.isEmpty(), "removed task resized replacement");
                check(replacement.windowRestoreBounds() == null, "removed task wrote replacement history");
                """);
    }

    @Test
    public void newestResizeSurvivesOlderCompletionAndFailureRestoresPreviousArrangement() throws Exception {
        verify("""
                f.sample(ordinary);
                Dispatch d = new Dispatch(f);
                d.snap(f.task, false, 0);
                d.snap(f.task, false, -1);
                f.complete(true);
                Rect corner = f.getSnappedBounds(false, -1);
                check(f.state().boundsTransition().targetBounds().equals(corner), "old callback cleared newer resize");
                f.complete(true);
                f.sample(corner);
                d.snap(f.task, false, 0);
                f.complete(false);
                f.sample(corner);
                check(f.state().arrangedWindowBounds().equals(corner), "failure forgot prior corner");
                check(f.state().windowRestoreBounds().equals(ordinary), "failure lost restore history");
                """);
    }

    @Test
    public void nativeMaximizeKeepsOrdinaryBoundsAndRestoreUsesThem() throws Exception {
        verify("""
                f.sample(ordinary);
                f.sample(display);
                check(f.requests.size() == 1, "native maximize needs one inset correction");
                check(f.requests.get(0).equals(work), "taskbar space must be reserved");
                f.complete(true);
                f.sample(work);
                f.sample(work);
                check(f.state().lastWindowBounds().equals(ordinary), "correction overwrote ordinary bounds");
                check(f.state().windowRestoreBounds().equals(ordinary), "restore history lost");
                Dispatch d = new Dispatch(f);
                d.applyRestoreShortcut(f.task);
                check(f.requests.get(1).equals(ordinary), "Win+Down did not restore native maximize");
                f.complete(true);
                f.sample(ordinary);
                d.applyRestoreShortcut(f.task);
                check(f.mRuntimeState.demotions == 1, "second Win+Down should demote");
                """);
    }

    @Test
    public void captionDragAndManualResizeDoNotPinMaximizedWindow() throws Exception {
        verify("""
                f.sample(ordinary);
                f.sample(work);
                Rect dragged = new Rect(-238, 177, 1682, 1193);
                f.sample(dragged);
                f.sample(dragged);
                check(f.requests.isEmpty(), "drag was reverted");
                check(f.state().windowRestoreBounds() == null, "drag retained maximized state");
                check(f.state().lastWindowBounds().equals(dragged), "drag was not accepted");
                Rect resized = new Rect(80, 90, 980, 790);
                f.sample(resized);
                f.sample(display);
                f.complete(true);
                f.sample(work);
                check(f.state().windowRestoreBounds().equals(resized), "next maximize lost resized bounds");
                """);
    }

    @Test
    public void nativeSnapAndRepeatedCorrectionKeepTheSameRestoreHistory() throws Exception {
        verify("""
                f.sample(ordinary);
                f.sample(new Rect(0, 0, 960, 1080));
                f.complete(true);
                f.sample(new Rect(0, 0, 960, 1016));
                f.sample(new Rect(960, 0, 2000, 1080));
                check(f.requests.get(1).equals(new Rect(960, 0, 2000, 1016)), "changed horizontal bounds");
                f.complete(true);
                f.sample(new Rect(960, 0, 2000, 1016));
                f.sample(display);
                f.complete(true);
                f.sample(work);
                check(f.state().windowRestoreBounds().equals(ordinary), "snap chain overwrote restore");
                check(f.requests.size() == 3, "unexpected restore or pinning transaction");
                """);
    }

    @Test
    public void restoredMaximizedLaunchHasUsableFallbackAndPhoneInsetsRemainReserved() throws Exception {
        verify("""
                f.sample(work);
                Rect restored = f.state().windowRestoreBounds();
                check(restored.width() < work.width() && restored.height() < work.height(), "no smaller fallback");
                f.sample(work);
                check(f.state().windowRestoreBounds().equals(restored), "fallback changed during observation");
                f.nativeArea = new Rect(0, 125, 1216, 2623);
                f.workArea = new Rect(0, 125, 1216, 2454);
                f.sample(new Rect(100, 200, 1000, 1000));
                f.sample(new Rect(137, 125, 1048, 2623));
                check(f.requests.get(0).equals(new Rect(137, 125, 1048, 2454)), "phone inset reservation changed");
                """);
    }

    @Test
    public void completedResizeDoesNotWaitForeverForAnExactRectangle() throws Exception {
        verify("""
                f.sample(ordinary);
                f.rememberRestoreBounds(f.task);
                Rect snap = new Rect(0, 0, 960, 1016);
                f.requestBounds(f.task, snap, true);
                f.sample(new Rect(0, 0, 1100, 1016));
                f.complete(true);
                check(f.state().boundsTransition() == null, "constrained resize remained pending");
                f.sample(new Rect(30, 70, 1130, 900));
                check(f.state().windowRestoreBounds() == null, "drag after constrained resize ignored");
                check(f.requests.size() == 1, "constrained resize was repeated");
                """);
    }

    @Test
    public void observationBeforeCompletionDoesNotEraseLaterArrangement() throws Exception {
        verify("""
                f.sample(ordinary);
                f.sample(display);
                f.sample(work);
                f.complete(true);
                check(f.state().windowRestoreBounds().equals(ordinary), "late completion erased restore");
                f.requestBounds(f.task, ordinary, false);
                f.sample(ordinary);
                f.sample(work);
                f.complete(true);
                check(f.state().windowRestoreBounds().equals(ordinary), "old restore completion erased new maximize");
                """);
    }

    @Test
    public void failedResizeKeepsRestoreAndRemovedTaskIgnoresCompletion() throws Exception {
        verify("""
                f.sample(ordinary);
                f.sample(work);
                f.requestBounds(f.task, ordinary, false);
                f.complete(false);
                check(f.state().windowRestoreBounds().equals(ordinary), "failure erased restore");
                check(f.state().boundsTransition() == null, "failed resize remained pending");
                f.requestBounds(f.task, ordinary, false);
                f.mTaskStates.states.clear();
                DesktopTaskRuntimeState replacement = f.state();
                replacement.setWindowRestoreBounds(work);
                f.complete(true);
                check(replacement.windowRestoreBounds().equals(work), "removed task completion changed replacement");
                """);
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", "static " + RuntimeSourceFixture.nestedClass("WindowMaximization", "WindowMaximization")
                .replace("HostedMaximization", "io.github.mekhontsev.magicdesk.hosted.HostedMaximization") + """
                static class Rect {
                    int left, top, right, bottom;
                    Rect() {}
                    Rect(int l, int t, int r, int b) { left=l; top=t; right=r; bottom=b; }
                    int width() { return right-left; }
                    int height() { return bottom-top; }
                    boolean isEmpty() { return width() <= 0 || height() <= 0; }
                    public boolean equals(Object o) { return o instanceof Rect r && left==r.left && top==r.top && right==r.right && bottom==r.bottom; }
                }
                static class DesktopTaskRuntimeState {
                    Rect mLastWindowBounds, mWindowRestoreBounds, mArrangedWindowBounds;
                    Rect mPendingSnapBounds, mFullscreenRestoreBounds;
                    enum FullscreenTransition { NONE, ENTERING, RESTORING }
                    FullscreenTransition mFullscreenTransition = FullscreenTransition.NONE;
                    BoundsTransition mBoundsTransition;
                    static class BoundsTransition {
                        final Rect target;
                        final boolean preservesRestoreBounds;
                        BoundsTransition(Rect target, boolean preserve) { this.target=copy(target); preservesRestoreBounds=preserve; }
                        Rect targetBounds() { return copy(target); }
                    }
                    boolean manualImmersiveOverride;
                    void setManualImmersiveOverride(boolean value) { manualImmersiveOverride = value; }
                    void setAppRequestedFullscreen(boolean value) {}
                """ + RuntimeSourceFixture.methods("DesktopTaskRuntimeState",
                "lastWindowBounds", "setLastWindowBounds", "windowRestoreBounds",
                "setWindowRestoreBounds", "clearWindowRestoreBounds", "beginBoundsTransition",
                "boundsTransition", "isBoundsTransition", "clearBoundsTransition",
                "arrangedWindowBounds", "setArrangedWindowBounds",
                "pendingSnapBounds", "setPendingSnapBounds",
                "fullscreenRestoreBounds", "setFullscreenRestoreBounds", "clearFullscreenRestoreBounds",
                "beginFullscreenRestoreTransition", "beginFullscreenTransition",
                "isFullscreenTransition", "finishFullscreenTransition",
                "clearNativeBoundsState", "copy") + """
                }
                static class States {
                    final Map<Integer, DesktopTaskRuntimeState> states = new HashMap<>();
                    DesktopTaskRuntimeState state(int id) { return states.computeIfAbsent(id, k -> new DesktopTaskRuntimeState()); }
                    DesktopTaskRuntimeState find(int id) { return states.get(id); }
                    boolean isCurrent(int id, DesktopTaskRuntimeState state) { return states.get(id)==state; }
                }
                static class RuntimeState {
                    int demotions;
                    Object windowContext() { return this; }
                    int displayId() { return 66; }
                    void scheduleRefresh() {}
                    void demoteTask(int id) { demotions++; }
                    void focusTask(int id) {}
                }
                static class TaskRepository {
                    static Fixture owner;
                    static class TaskEntry {
                        int taskId=50350, displayId=66;
                        boolean visible=true;
                        boolean fullscreen;
                        Rect bounds;
                        boolean isBoundedFreeform() { return true; }
                        boolean isFullscreen() { return fullscreen; }
                        boolean isFreeform() { return !fullscreen; }
                        boolean hasCrossPackageTopActivity() { return false; }
                    }
                    record ActionResult(boolean success, String message) {}
                    interface ActionCallback { void onComplete(ActionResult result); }
                    static boolean hasExplicitBounds(Rect bounds) { return bounds != null && !bounds.isEmpty(); }
                    static void resizeTaskBounds(TaskEntry task, Rect bounds, ActionCallback callback) {
                        owner.requests.add(bounds); owner.callbacks.add(callback);
                    }
                }
                static class DesktopManagedTaskPolicy { static boolean isControllableApplicationTask(Object task) { return true; } }
                static class Handler { void post(Runnable runnable) { runnable.run(); } }
                static class Log { static void w(String tag, String message, Object... error) {} }
                static class FloatingWindowController {
                    static Rect getDefaultWindowBounds(int display) throws IOException { return new Rect(50,50,900,800); }
                }
                record DesktopWindowTransitionRequest(Rect bounds) {
                    static DesktopWindowTransitionRequest restoreFreeform(int display, int task, Rect bounds, int density, String reason) {
                        return new DesktopWindowTransitionRequest(bounds);
                    }
                }
                static final String TAG = "fixture";
                final States mTaskStates = new States();
                final RuntimeState mRuntimeState = new RuntimeState();
                final Handler mHandler = new Handler();
                final List<Rect> requests = new ArrayList<>();
                final Queue<TaskRepository.ActionCallback> callbacks = new ArrayDeque<>();
                final TaskRepository.TaskEntry task = new TaskRepository.TaskEntry();
                Rect nativeArea = new Rect(0,0,1920,1080);
                Rect workArea = new Rect(0,0,1920,1016);
                Rect getNativeCaptionSnapArea() { return nativeArea; }
                Rect getTaskbarMaximizedBounds() { return workArea; }
                DesktopTaskRuntimeState state() { return mTaskStates.state(task.taskId); }
                void sample(Rect bounds) { task.bounds=bounds; reconcile(List.of(task)); }
                void complete(boolean success) { callbacks.remove().onComplete(new TaskRepository.ActionResult(success, "fixture")); }
                static class Dispatch {
                    enum RestoreShortcutAction { RESTORE_FULLSCREEN, RESTORE_WINDOW_BOUNDS, DEMOTE }
                    final States mTaskStates;
                    final RuntimeState mRuntimeState;
                    final Fixture mNativeWindowBounds;
                    final Handler mHandler = new Handler();
                    final List<DesktopWindowTransitionRequest> exits = new ArrayList<>();
                    final Queue<TaskRepository.ActionCallback> exitCallbacks = new ArrayDeque<>();
                    Dispatch(Fixture f) { mTaskStates=f.mTaskStates; mRuntimeState=f.mRuntimeState; mNativeWindowBounds=f; }
                    void restoreFullscreenTask(TaskRepository.TaskEntry task, boolean requested) { throw new AssertionError("unexpected fullscreen restore"); }
                    int densityFor(TaskRepository.TaskEntry task) { return 0; }
                    void rememberWindowed(TaskRepository.TaskEntry task, Rect bounds, Rect work) {}
                    void submitRequired(DesktopWindowTransitionRequest request, TaskRepository.ActionCallback callback) {
                        exits.add(request); exitCallbacks.add(callback);
                    }
                """ + RuntimeSourceFixture.methods("DesktopWindowTransitionController",
                "applyRestoreShortcut", "classifyRestoreShortcut", "setWindowBounds", "setMaximized", "complete",
                "snap", "arrange", "snapFullscreenTask", "noteManualFreeformTransition") + """
                }
                public static void verify() {
                    Fixture f = new Fixture();
                    TaskRepository.owner = f;
                    Rect ordinary = new Rect(360,142,1560,874);
                    Rect display = f.nativeArea;
                    Rect work = f.workArea;
                """ + scenario + "}\n" + RuntimeSourceFixture.methods(
                "NativeWindowBoundsController", "reconcile", "observeBounds",
                "getSnappedBounds", "snappedBounds",
                "rememberRestoreBounds", "occupiesHeight", "getDefaultWindowBounds",
                "correctNativeCaptionSnapBounds", "sameBounds", "rect", "requestBounds", "complete"),
                java.nio.file.Path.of("../hosted-runtime/src/main/java/io/github/mekhontsev/magicdesk/hosted/HostedMaximization.java").toAbsolutePath().toString());
    }
}
