package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class NativeWindowBoundsReconciliationTest {
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
        RuntimeSourceFixture.verify("""
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
                    Rect mLastWindowBounds, mWindowRestoreBounds;
                    BoundsTransition mBoundsTransition;
                    static class BoundsTransition {
                        final Rect target;
                        final boolean preservesRestoreBounds;
                        BoundsTransition(Rect target, boolean preserve) { this.target=copy(target); preservesRestoreBounds=preserve; }
                        Rect targetBounds() { return copy(target); }
                    }
                    boolean isFullscreenTransition() { return false; }
                    Rect fullscreenRestoreBounds() { return null; }
                    void setManualImmersiveOverride(boolean value) {}
                """ + RuntimeSourceFixture.methods("DesktopTaskRuntimeState",
                "lastWindowBounds", "setLastWindowBounds", "windowRestoreBounds",
                "setWindowRestoreBounds", "clearWindowRestoreBounds", "beginBoundsTransition",
                "boundsTransition", "isBoundsTransition", "clearBoundsTransition",
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
                }
                static class TaskRepository {
                    static Fixture owner;
                    static class TaskEntry {
                        int taskId=50350, displayId=66;
                        boolean visible=true;
                        Rect bounds;
                        boolean isBoundedFreeform() { return true; }
                        boolean isFullscreen() { return false; }
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
                static class Log { static void w(String tag, String message) {} }
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
                    Dispatch(Fixture f) { mTaskStates=f.mTaskStates; mRuntimeState=f.mRuntimeState; mNativeWindowBounds=f; }
                    void restoreFullscreenTask(TaskRepository.TaskEntry task, boolean requested) { throw new AssertionError("unexpected fullscreen restore"); }
                """ + RuntimeSourceFixture.methods("DesktopWindowTransitionController",
                "applyRestoreShortcut", "classifyRestoreShortcut", "setWindowBounds") + """
                }
                public static void verify() {
                    Fixture f = new Fixture();
                    TaskRepository.owner = f;
                    Rect ordinary = new Rect(360,142,1560,874);
                    Rect display = f.nativeArea;
                    Rect work = f.workArea;
                """ + scenario + "}\n" + RuntimeSourceFixture.methods(
                "NativeWindowBoundsController", "reconcile", "observeBounds",
                "rememberRestoreBounds", "occupiesHeight", "getDefaultWindowBounds",
                "correctNativeCaptionSnapBounds", "sameBounds", "rect", "requestBounds", "complete"));
    }
}
