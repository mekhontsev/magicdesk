package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class FullscreenRestoreBoundsRegressionTest {
    @Test
    public void explicitSnapBoundsWinOverAppRestoreBounds() throws Exception {
        verify("""
                check(f.restoreTask(f, 4, 42, explicit, 0), "restore rejected");
                check(f.mPlanes.applied == explicit, "saved app bounds replaced explicit snap geometry");
                check(f.mAppRestoreBounds.isEmpty(), "successful restore retained app bounds");
                """);
    }

    @Test
    public void absentRequestUsesSavedAppBounds() throws Exception {
        verify("""
                check(f.restoreTask(f, 4, 42, null, 0), "saved restore rejected");
                check(f.mPlanes.applied == saved, "saved fallback bounds lost");
                """);
    }

    @Test
    public void nativeFullscreenRestoresWithoutAcquiringPlane() throws Exception {
        verify("""
                f.mPlanes.owned = false;
                check(f.restoreTask(f, 4, 42, explicit, 240), "native restore rejected");
                check(f.mPlanes.calls == 0, "native restore entered plane path");
                check(f.applied == explicit, "native restore changed requested bounds");
                check(f.density == 240, "native restore lost application density");
                check(f.committed == explicit, "native restore omitted bounds confirmation");
                check(f.mAppRestoreBounds.isEmpty(), "native restore retained app bounds");
                """);
    }

    @Test
    public void phoneDesktopUsesSameNativeRestorePath() throws Exception {
        verify("""
                f.mDisplayId = 0;
                f.mPlanes.owned = false;
                check(f.restoreTask(f, 0, 42, explicit, 0), "phone restore rejected");
                check(f.applied == explicit && f.committed == explicit, "phone restore bypassed confirmation");
                """);
    }

    @Test
    public void failedPlaneRestoreDoesNotFallThroughToNativeTransition() throws Exception {
        verify("""
                f.mPlanes.result = false;
                check(!f.restoreTask(f, 4, 42, explicit, 0), "failed plane restore accepted");
                check(f.mPlanes.calls == 1, "plane restore not attempted");
                check(f.applied == null, "failed plane restore used raw fallback");
                check(f.mAppRestoreBounds.get(42) == saved, "failure lost restore bounds");
                """);
    }

    @Test
    public void unownedAndWrongDisplayTasksCannotBeRestored() throws Exception {
        verify("""
                check(!f.restoreTask(f, 3, 42, explicit, 0), "wrong display accepted");
                f.desktopOwned = false;
                check(!f.restoreTask(f, 4, 42, explicit, 0), "unowned task accepted");
                check(f.mPlanes.calls == 0 && f.applied == null, "rejected task mutated");
                check(f.mAppRestoreBounds.get(42) == saved, "rejection lost restore bounds");
                """);
    }

    @Test
    public void missingTaskCannotStartRestore() throws Exception {
        verify("""
                f.missing = true;
                check(!f.restoreTask(f, 4, 42, explicit, 0), "missing task accepted");
                check(f.mPlanes.calls == 0 && f.applied == null, "missing task mutated");
                check(f.mAppRestoreBounds.get(42) == saved, "missing task lost restore bounds");
                """);
    }

    @Test
    public void nativeTransitionMustCommitBeforeReportingSuccess() throws Exception {
        verify("""
                f.mPlanes.owned = false;
                f.failCommit = true;
                check(!f.restoreTask(f, 4, 42, explicit, 0), "unconfirmed restore accepted");
                check(f.applied == explicit, "native transition not submitted");
                check(f.mAppRestoreBounds.get(42) == saved, "failed commit lost restore bounds");
                """);
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static class Rect { boolean isEmpty() { return false; } }
                static class Planes {
                    Rect applied;
                    boolean owned = true, result = true;
                    int calls;
                    boolean ownsTask(int task) { return owned; }
                    boolean restoreFreeform(Object service, int display, int task, Rect bounds, int density) {
                        calls++;
                        applied = bounds;
                        return result;
                    }
                }
                static class Ownership {
                    boolean isDesktopTask(Object task) { return ((Fixture) task).desktopOwned; }
                }
                static class HiddenTaskApi {
                    static Object requireTask(Object service, int display, int task) throws ReflectiveOperationException {
                        Fixture f = (Fixture) service;
                        check(task == 42 && display == f.mDisplayId, "restore changed task or display");
                        if (f.missing) throw new IllegalStateException("task removed");
                        return service;
                    }
                }
                static class ShellPreparedTaskTransition {
                    static void applyFreeform(Object service, int display, int task, Rect bounds, int density) {
                        Fixture f = (Fixture) service;
                        check(task == 42 && display == f.mDisplayId, "restore changed task or display");
                        f.applied = bounds;
                        f.density = density;
                    }
                }
                static class TaskDisplayAreaLaunchCommand {
                    static void waitForTaskFreeformBounds(Object service, int display, int task, Rect bounds) {
                        Fixture f = (Fixture) service;
                        check(task == 42 && display == f.mDisplayId, "confirmation changed task or display");
                        if (f.failCommit) throw new IllegalStateException("freeform bounds not committed");
                        f.committed = bounds;
                    }
                }
                static class Log { static void w(String tag, String message, Throwable error) {} }
                static final String TAG = "fixture";
                final Map<Integer, Rect> mAppRestoreBounds = new HashMap<>();
                final Planes mPlanes = new Planes();
                final Ownership mOwnership = new Ownership();
                boolean desktopOwned = true, missing, failCommit;
                Rect applied, committed;
                int density;
                int mDisplayId = 4;
                public static void verify() {
                    Fixture f = new Fixture();
                    Rect saved = new Rect(), explicit = new Rect();
                    f.mAppRestoreBounds.put(42, saved);
                """ + scenario + "}\n" + RuntimeSourceFixture.methods(
                "ShellFullscreenTaskArea", "restoreTask"));
    }
}
