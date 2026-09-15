package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class VirtualDisplayPresentationTest {
    @Test public void presentationPowerIsReleasedOnDetachAndSurfaceFailure() throws Exception {
        RuntimeSourceFixture.verify("""
                static class android {
                    static class view {
                        static class Surface { boolean isValid() { return true; } }
                        static class SurfaceControl { }
                    }
                }
                interface DisplayPresentationSurface {
                    void attach(android.view.Surface surface, android.view.SurfaceControl parent);
                    void close();
                }
                boolean mReleased;
                int mPresentations;
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
                DisplayPresentationSurface direct() {
                    return presentation(new DisplayPresentationSurface() {
                        public void attach(android.view.Surface surface, android.view.SurfaceControl parent) { present(surface); }
                        public void close() { detachViewer(); }
                    });
                }
                public static void verify() {
                    Fixture f = new Fixture();
                    android.view.Surface viewer = new android.view.Surface();
                    var primary = f.direct();
                    primary.attach(viewer, null);
                    check(f.mDisplay.surface == viewer && f.mPresentationWakeLock.held,
                            "presentation has no power ownership");
                    primary.close();
                    check(f.mDisplay.surface == f.mOutput.sink && !f.mPresentationWakeLock.held,
                            "detach did not release presentation power");
                    primary.close();
                    check(f.mPresentationWakeLock.releases == 1, "double power release");
                    f.mDisplay.fail = true;
                    try { f.direct().attach(viewer, null); throw new AssertionError("failure ignored"); }
                    catch (IllegalStateException expected) { }
                    check(!f.mPresentationWakeLock.held, "failed presentation leaked power");
                    f.mDisplay.fail = false;
                    primary = f.direct(); primary.attach(viewer, null);
                    f.mDisplay.fail = true;
                    try { primary.close(); throw new AssertionError("failure ignored"); }
                    catch (IllegalStateException expected) { }
                    check(!f.mPresentationWakeLock.held, "failed detach leaked power");
                    f.mReleased = true;
                    try { f.direct().attach(viewer, null); throw new AssertionError("removed display accepted"); }
                    catch (IllegalStateException expected) { }
                    check(!f.mPresentationWakeLock.held, "removed source acquired power");

                    f.mReleased = false; f.mDisplay.fail = false;
                    primary = f.direct(); primary.attach(viewer, null);
                    int[] mirrorCloses = {0};
                    var mirror = f.presentation(new DisplayPresentationSurface() {
                        public void attach(android.view.Surface surface, android.view.SurfaceControl parent) { }
                        public void close() { mirrorCloses[0]++; }
                    });
                    mirror.attach(viewer, null); mirror.attach(viewer, null);
                    check(f.mPresentations == 2 && f.mDisplay.surface == viewer,
                            "mirror stole primary output or counted reattach twice");
                    primary.close();
                    check(f.mPresentationWakeLock.held && f.mDisplay.surface == f.mOutput.sink,
                            "closing primary put a mirrored source to sleep");
                    mirror.close(); mirror.close();
                    check(!f.mPresentationWakeLock.held && f.mPresentations == 0 && mirrorCloses[0] == 1,
                            "mirror power/cleanup was not released exactly once");
                }
                """ + RuntimeSourceFixture.methods("FrameworkVirtualDisplayApi", "present", "detachViewer",
                        "presentation", "acquirePresentation", "releasePresentation", "releasePresentationWakeLock"));
    }
}
