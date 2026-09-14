package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class VirtualDisplayPresentationTest {
    @Test public void presentationPowerIsReleasedOnDetachAndSurfaceFailure() throws Exception {
        RuntimeSourceFixture.verify("""
                static class android {
                    static class view {
                        static class Surface { boolean isValid() { return true; } }
                    }
                }
                boolean mReleased;
                static class Lock {
                    boolean held;
                    int releases;
                    boolean isHeld() { return held; }
                    void release() { held = false; releases++; }
                }
                static class Display {
                    boolean fail;
                    android.view.Surface surface;
                    void setSurface(android.view.Surface surface) {
                        if (fail) throw new IllegalStateException("surface unavailable");
                        this.surface = surface;
                    }
                }
                static class Output {
                    final android.view.Surface sink = new android.view.Surface();
                    android.view.Surface getSurface() { return sink; }
                }
                final Display mDisplay = new Display();
                final Output mOutput = new Output();
                final Lock mPresentationWakeLock = new Lock();
                void keepPresentationAwake() { mPresentationWakeLock.held = true; }
                public static void verify() {
                    Fixture f = new Fixture();
                    android.view.Surface viewer = new android.view.Surface();
                    f.present(viewer);
                    check(f.mDisplay.surface == viewer && f.mPresentationWakeLock.held,
                            "presentation has no power ownership");
                    f.detachViewer();
                    check(f.mDisplay.surface == f.mOutput.sink && !f.mPresentationWakeLock.held,
                            "detach did not release presentation power");
                    f.detachViewer();
                    check(f.mPresentationWakeLock.releases == 1, "double power release");
                    f.mDisplay.fail = true;
                    try { f.present(viewer); throw new AssertionError("failure ignored"); }
                    catch (IllegalStateException expected) { }
                    check(!f.mPresentationWakeLock.held, "failed presentation leaked power");
                    f.mPresentationWakeLock.held = true;
                    try { f.detachViewer(); throw new AssertionError("failure ignored"); }
                    catch (IllegalStateException expected) { }
                    check(!f.mPresentationWakeLock.held, "failed detach leaked power");
                    f.mReleased = true;
                    try { f.present(viewer); throw new AssertionError("removed display accepted"); }
                    catch (IllegalStateException expected) { }
                    check(!f.mPresentationWakeLock.held, "removed source acquired power");
                }
                """ + RuntimeSourceFixture.methods("FrameworkVirtualDisplayApi", "present", "detachViewer",
                        "releasePresentationWakeLock"));
    }
}
