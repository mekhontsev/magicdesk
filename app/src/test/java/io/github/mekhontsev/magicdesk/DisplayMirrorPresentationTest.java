package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DisplayMirrorPresentationTest {
    @Test public void mirrorOwnsOnlyItsCopiedSceneAndCleansFailedAttachments() throws Exception {
        RuntimeSourceFixture.verify("""
                interface DisplayPresentationSurface extends AutoCloseable {
                    void attach(Surface surface, SurfaceControl parent);
                    void close();
                }
                static class Surface { }
                public static class SurfaceControl {
                    static int releases, attached, detached;
                    boolean valid = true;
                    public SurfaceControl() { }
                    boolean isValid() { return valid; }
                    void release() { releases++; valid = false; }
                    static class Transaction implements AutoCloseable {
                        Transaction reparent(SurfaceControl child, SurfaceControl parent) {
                            if (parent == null) detached++; else attached++;
                            return this;
                        }
                        Transaction setLayer(SurfaceControl child, int layer) { return this; }
                        Transaction setVisibility(SurfaceControl child, boolean visible) { return this; }
                        void apply() { }
                        public void close() { }
                    }
                }
                public static class Windows {
                    int calls;
                    boolean available = true;
                    public boolean mirrorDisplay(int id, SurfaceControl result) {
                        check(id == 7, "source identity changed"); calls++; return available;
                    }
                }
                final Windows mWindows = new Windows();
                final java.lang.reflect.Method mMirror;
                Fixture() throws Exception {
                    mMirror = Windows.class.getMethod("mirrorDisplay", int.class, SurfaceControl.class);
                }
                public static void verify() throws Exception {
                    Fixture f = new Fixture();
                    DisplayPresentationSurface p = f.create(7);
                    check(f.mWindows.calls == 0, "unattached lease created mirror resources");
                    SurfaceControl parent = new SurfaceControl();
                    p.attach(new Surface(), parent);
                    check(f.mWindows.calls == 1 && SurfaceControl.attached == 1, "mirror not attached");
                    p.attach(new Surface(), parent);
                    check(SurfaceControl.releases == 1 && SurfaceControl.detached == 1,
                            "reattach leaked old mirror");
                    p.close(); p.close();
                    check(SurfaceControl.releases == 2 && parent.isValid(), "close released source/parent");
                    f.mWindows.available = false;
                    try { p.attach(new Surface(), parent); throw new AssertionError("failure ignored"); }
                    catch (IllegalStateException expected) { }
                    check(SurfaceControl.releases == 3, "failed creation leaked surface");
                    p.close();
                    check(SurfaceControl.releases == 3, "failed attachment released twice");
                    parent.valid = false;
                    try { p.attach(new Surface(), parent); throw new AssertionError("invalid parent accepted"); }
                    catch (IllegalArgumentException expected) { }
                    check(f.mWindows.calls == 3, "invalid parent created a mirror");
                }
                """ + RuntimeSourceFixture.methods("FrameworkDisplayMirrorApi", "create"));
    }
}
