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

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static class Rect { boolean isEmpty() { return false; } }
                static class Planes {
                    Rect applied;
                    boolean restoreFreeform(Object service, int display, int task, Rect bounds, int density) { applied = bounds; return true; }
                }
                final Map<Integer, Rect> mAppRestoreBounds = new HashMap<>();
                final Planes mPlanes = new Planes();
                int mDisplayId = 4;
                public static void verify() {
                    Fixture f = new Fixture();
                    Rect saved = new Rect(), explicit = new Rect();
                    f.mAppRestoreBounds.put(42, saved);
                """ + scenario + "}\n" + RuntimeSourceFixture.methods(
                "ShellFullscreenTaskArea", "restoreTask"));
    }
}
