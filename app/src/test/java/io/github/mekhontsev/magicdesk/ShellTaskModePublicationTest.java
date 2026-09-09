package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class ShellTaskModePublicationTest {
    @Test
    public void failedAdoptionStillPublishesModeAndCaptionSource() throws Exception {
        verify("""
                f.mFullscreenTaskArea.fail = true;
                f.onWindowingModeChanged(66, 42, 5, 1, 123, true);
                check(f.mCallback.errors == 1, "ownership error was swallowed");
                check(f.mCallback.changes == 1 && f.mCallback.captionSource == 123,
                        "failed adoption suppressed caption refresh event");
                check(!f.mCallback.released, "failed adoption claimed a background release");
                check(f.mSelfTestTaskStackGuard.samples == 1, "mode observation was interrupted");
                """);
    }

    @Test
    public void successfulReleaseKeepsItsPublication() throws Exception {
        verify("""
                f.mFullscreenTaskArea.released = true;
                f.onWindowingModeChanged(66, 42, 1, 5, -1, false);
                check(f.mCallback.errors == 0 && f.mCallback.changes == 1 && f.mCallback.released,
                        "confirmed release was lost");
                """);
    }

    @Test
    public void unrelatedTasksDoNotReachDesktopCaptionPolicy() throws Exception {
        verify("""
                f.mDesktopOwnership.remembered = false;
                f.onWindowingModeChanged(66, 42, 5, 1, 123, true);
                check(f.mCallback.changes == 0, "unowned task reached desktop policy");
                """);
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static final String TAG = "fixture";
                static class Log { static void w(String tag, String message, Throwable error) {} }
                static class Area {
                    boolean fail, released;
                    boolean onWindowingModeChanged(Object service, int display, int task, int mode, boolean focused) {
                        if (fail) throw new IllegalStateException("workspace incomplete");
                        return released;
                    }
                }
                static class Guard {
                    int samples;
                    void sample(String reason) { samples++; }
                }
                static class Ownership {
                    boolean remembered = true;
                    boolean isRememberedDesktopTask(int id) { return remembered; }
                }
                static class Callback {
                    int errors, changes, captionSource;
                    boolean released;
                    void onObserverError(String error) { errors++; }
                    void onWindowingModeChanged(int task, int previous, int mode, int source, boolean background) {
                        changes++; captionSource = source; released = background;
                    }
                }
                final Object mService = new Object();
                final Area mFullscreenTaskArea = new Area();
                final Guard mSelfTestTaskStackGuard = new Guard();
                final Ownership mDesktopOwnership = new Ownership();
                final Callback mCallback = new Callback();
                void callCallback(Runnable callback) { callback.run(); }
                public static void verify() {
                    Fixture f = new Fixture();
                """ + scenario + "}\n" + RuntimeSourceFixture.methods(
                "ShellTaskObserver", "onWindowingModeChanged"));
    }
}
