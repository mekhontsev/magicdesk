package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopTaskbarHostOwnershipTest {
    @Test
    public void oldHostCannotDetachOrReplaceNewTaskbar() throws Exception {
        RuntimeSourceFixture.verify("""
                static final Object REGISTRY_LOCK = new Object();
                static final Map<Integer, Fixture> HOSTS = new HashMap<>();
                static final Map<Integer, DesktopChromeActivity> ACTIVITIES = new HashMap<>();
                final int mDisplayId = 4;
                final Rect mTaskbarBounds = new Rect(), mSurfaceBounds = new Rect(), mAppliedBounds = new Rect();
                Object mTaskbar = new Object(), mEdgeInputListener;
                boolean mReleased, mPresented = true, mEdgeHidden;
                int mEdgeHeight = 1;
                static final class Rect { void setEmpty() {} int height() { return 64; } }
                static final class DesktopChromeActivity {
                    int attached, detached;
                    void attachTaskbar(Object view, int height, int surfaceHeight) { attached++; }
                    void detachTaskbar() { detached++; }
                    void setPresentation(boolean shown, boolean edge, int height) {}
                }
                public static void verify() {
                    Fixture oldHost = new Fixture(), replacement = new Fixture();
                    DesktopChromeActivity activity = new DesktopChromeActivity();
                    ACTIVITIES.put(4, activity);
                    HOSTS.put(4, oldHost);
                    oldHost.apply(activity);
                    HOSTS.put(4, replacement);
                    replacement.apply(activity);
                    check(activity.attached == 2, "initial hosts did not attach");
                    check(oldHost.currentActivity() == null, "old host retained chrome ownership");
                    oldHost.apply(activity);
                    check(activity.attached == 2, "old host replaced the new taskbar");
                    oldHost.release();
                    check(activity.detached == 0, "old host detached the new taskbar");
                    check(HOSTS.get(4) == replacement, "old release removed new registration");
                    replacement.release();
                    check(activity.detached == 1, "current host did not detach");
                    check(!HOSTS.containsKey(4), "released host remained registered");
                    replacement.release();
                    check(activity.detached == 1, "release was not idempotent");
                }
                """ + RuntimeSourceFixture.methods(
                "DesktopTaskbarHost", "release", "currentActivity", "apply"));
    }
}
