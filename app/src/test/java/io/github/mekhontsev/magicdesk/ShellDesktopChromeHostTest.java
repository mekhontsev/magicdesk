package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class ShellDesktopChromeHostTest {
    @Test public void ordinaryPreparationDoesNotRequireSurfaceInput() throws Exception {
        verify("""
                deny = true;
                check(host.prepare(7, false) == 10, "ordinary host unavailable");
                check(grants == 0 && queues == 0, "optional capability initialized at startup");
                try { host.prepare(8, true); throw new AssertionError("stale display accepted"); }
                catch (IllegalStateException expected) { }
                check(grants == 0, "stale request touched a surface");
                """);
    }

    @Test public void grantBelongsToOneHostLifetime() throws Exception {
        verify("""
                host.prepare(7, true);
                host.prepare(7, true);
                host.prepare(7, false);
                check(grants == 1 && queues == 1, "grant not reused");
                host.live = false;
                host.prepare(7, true);
                check(grants == 2 && queues == 2, "replacement host inherited stale grant");
                """);
    }

    @Test public void deniedOptionalGrantLeavesOrdinaryDesktopUsable() throws Exception {
        verify("""
                deny = true;
                try { host.prepare(7, true); throw new AssertionError("denial accepted"); }
                catch (SecurityException expected) { }
                check(!host.mTrustedOverlay, "failed grant published");
                check(host.prepare(7, false) == 10, "denial disabled ordinary panels");
                deny = false;
                host.prepare(7, true);
                check(grants == 2 && host.mTrustedOverlay, "explicit retry not granted");
                """);
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static int grants, queues;
                static boolean deny;
                static class Display { static final int INVALID_DISPLAY = -1; }
                static class TaskDisplayAreaHandle {
                    Object surfaceLeash() { return this; }
                }
                static class ShellWindowTransitionExecutor {
                    static void prepareSurfaceTransactions() { queues++; }
                }
                static class FrameworkRuntime {
                    static FrameworkRuntime current() { return new FrameworkRuntime(); }
                    FrameworkRuntime surfaceInput() { return this; }
                    void trustOwnedOverlay(Object context, Object area) throws ReflectiveOperationException {
                        check(context != null && area instanceof TaskDisplayAreaHandle, "owner lost");
                        grants++;
                        if (deny) throw new SecurityException("denied");
                    }
                }
                final Object mContext = new Object();
                TaskDisplayAreaHandle mArea = new TaskDisplayAreaHandle();
                int mDisplayId = 7, mTaskId = 10;
                boolean mFocusable, mTrustedOverlay, live = true;
                Object findOwnedTask() { return live ? new Object() : null; }
                void configure(int display) {
                    clearState();
                    mArea = new TaskDisplayAreaHandle();
                    mDisplayId = display; mTaskId = 10; live = true;
                }
                public static void verify() throws Exception {
                    final var host = new Fixture();
                """ + scenario + "}\n"
                + RuntimeSourceFixture.methods("ShellDesktopChromeHost", "prepare", "clearState"));
    }
}
