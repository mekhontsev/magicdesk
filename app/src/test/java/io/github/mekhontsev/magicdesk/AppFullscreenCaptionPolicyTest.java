package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class AppFullscreenCaptionPolicyTest {
    @Test
    public void applicationEntryUsesSessionCaptionPolicy() throws Exception {
        verifyObserver(true);
        verifyObserver(false);
    }

    @Test
    public void planeEntryRetainsCaptionPolicyAndRestoreGeometry() throws Exception {
        verifyArea(true, true);
        verifyArea(false, true);
    }

    @Test
    public void rejectedEntryDoesNotSaveRestoreGeometry() throws Exception {
        verifyArea(true, false);
    }

    private static void verifyObserver(boolean enabled) throws Exception {
        RuntimeSourceFixture.verify("""
                static class Rect {}
                static class Ownership { void markDesktop(int task) { check(task == 42, "wrong task"); } }
                class Area {
                    boolean beginAppFullscreen(Object service, int display, int task, Rect bounds,
                            boolean refresh, int density) {
                        check(service == mService && display == 4 && task == 42, "entry identity changed");
                        check(bounds == restore && density == 240, "entry geometry changed");
                        check(refresh == policy, "application entry bypassed session caption policy");
                        calls++;
                        return true;
                    }
                }
                final Object mService = new Object();
                final Ownership mDesktopOwnership = new Ownership();
                final Area mFullscreenTaskArea = new Area();
                final Rect restore = new Rect();
                int mConfiguredDisplayId = 4, calls;
                boolean mClosed, policy;
                boolean refreshFullscreenCaption() { return policy; }
                void reportDesktopTaskOwnership() {}
                public static void verify() {
                    Fixture f = new Fixture();
                """ + "f.policy = " + enabled + ";\n" + """
                    check(f.beginAppFullscreenTask(4, 42, f.restore, 240), "entry rejected");
                    check(f.calls == 1, "entry must submit exactly once");
                    check(!f.beginAppFullscreenTask(5, 42, f.restore, 240), "foreign display accepted");
                    f.mClosed = true;
                    check(!f.beginAppFullscreenTask(4, 42, f.restore, 240), "closed observer accepted");
                    check(f.calls == 1, "rejected entry mutated task");
                }
                """ + RuntimeSourceFixture.methods("ShellTaskObserver", "beginAppFullscreenTask"));
    }

    private static void verifyArea(boolean enabled, boolean accepted) throws Exception {
        RuntimeSourceFixture.verify("""
                static class Rect {
                    boolean empty;
                    Rect() {}
                    Rect(Rect other) { empty = other.empty; }
                    boolean isEmpty() { return empty; }
                }
                class Planes {
                    boolean beginFullscreen(Object service, int display, int task, boolean refresh,
                            int density, Object ownership) {
                        check(service == Fixture.this && display == 4 && task == 42, "entry identity changed");
                        check(density == 240 && ownership == mOwnership, "entry ownership changed");
                        check(refresh == policy, "plane entry discarded caption policy");
                        calls++;
                        return accepted;
                    }
                }
                final Planes mPlanes = new Planes();
                final Object mOwnership = new Object();
                final Map<Integer, Rect> mAppRestoreBounds = new HashMap<>();
                int mDisplayId = 4, calls;
                boolean policy, accepted;
                public static void verify() {
                    Fixture f = new Fixture();
                    Rect restore = new Rect();
                """ + "f.policy = " + enabled + "; f.accepted = " + accepted + ";\n" + """
                    check(f.beginAppFullscreen(f, 4, 42, restore, f.policy, 240) == f.accepted,
                            "entry result changed");
                    check(f.calls == 1, "entry must submit exactly once");
                    check(f.mAppRestoreBounds.containsKey(42) == f.accepted, "invalid restore ownership");
                    if (f.accepted) check(f.mAppRestoreBounds.get(42) != restore, "restore bounds not copied");
                    check(!f.beginAppFullscreen(f, 5, 42, restore, f.policy, 240), "foreign display accepted");
                    check(!f.beginAppFullscreen(f, 4, 42, null, f.policy, 240), "missing bounds accepted");
                    restore.empty = true;
                    check(!f.beginAppFullscreen(f, 4, 42, restore, f.policy, 240), "empty bounds accepted");
                    check(f.calls == 1, "rejected entry mutated task");
                }
                """ + RuntimeSourceFixture.methods("ShellFullscreenTaskArea", "beginAppFullscreen"));
    }
}
