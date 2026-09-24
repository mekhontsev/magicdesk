package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class HostedFamilyInputTest {
    @Test public void keyboardLeaseSurvivesInternalFocusAndChildRemoval() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk",
                RuntimeSourceFixture.methods("HostedSurfaceView", "releaseKeys", "releaseHeldKeys",
                        "shareKeyboard", "leaveKeyboardFamily", "releaseFamilyInput").replace("HostedSurfaceView", "Fixture") + """
            static class SparseIntArray {
                final TreeMap<Integer,Integer> values = new TreeMap<>();
                void put(int key, int scan) { values.put(key,scan); }
                int size() { return values.size(); }
                int keyAt(int at) { return new ArrayList<>(values.keySet()).get(at); }
                int valueAt(int at) { return new ArrayList<>(values.values()).get(at); }
                void clear() { values.clear(); }
            }
            static class Output { int releases; void key(int key, int scan, boolean down) { if (!down) releases++; } }
            static class Pointer { int releases; void release() { releases++; } }
            SparseIntArray keys = new SparseIntArray();
            boolean borrowedKeyboard, contentDrag;
            Runnable focusBoundary;
            Output output = new Output();
            Pointer pointerInput = new Pointer();
            public static void verify() {
                var parent = new Fixture(); var child = new Fixture();
                parent.focusBoundary = child.focusBoundary = () -> {};
                parent.keys.put(113,29);
                child.shareKeyboard(parent);
                child.releaseKeys(); parent.releaseKeys();
                check(parent.keys.size() == 1 && child.output.releases == 0, "internal focus released Ctrl");
                child.leaveKeyboardFamily(); child.focusBoundary = null; child.releaseKeys();
                check(parent.keys.size() == 1 && child.keys.size() == 0, "child closure lost parent's key lease");
                child.shareKeyboard(parent); child.focusBoundary = () -> {};
                child.releaseFamilyInput(); parent.releaseFamilyInput();
                check(parent.keys.size() == 0 && parent.output.releases + child.output.releases == 1,
                    "leaving family must release shared keys exactly once");
                check(parent.pointerInput.releases == 1 && child.pointerInput.releases == 1, "pointer cleanup missing");
                parent.focusBoundary = null; parent.keys.put(59,42); parent.releaseKeys();
                check(parent.keys.size() == 0 && parent.output.releases == 1, "ordinary host focus behavior changed");
            }
            """);
    }

    @Test public void borrowedSurfaceDetachesOnceAndLateCallbacksCannotDetachReplacement() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk",
                RuntimeSourceFixture.methods("HostedShellSurfaceView", "close", "surfaceChanged") + """
            static class Surface { }
            static class Output {
                int detaches;
                void setSurface(Surface surface, int w, int h) { if (surface == null) detaches++; }
            }
            static class Admission { void revoke() { } }
            class Content { void release() { surfaceChanged(null,0,0); } }
            boolean closed, ownsOutput;
            Object frame;
            Surface surface;
            int surfaceWidth, surfaceHeight;
            final Output output = new Output();
            final Admission admission = new Admission();
            final Content content = new Content();
            void invalidatePresentation() { }
            void clearInput() { }
            void cancelPending(String reason) { }
            void checkThread() { }
            public static void verify() {
                var fixture = new Fixture();
                fixture.surfaceChanged(new Surface(),80,40);
                fixture.close(); fixture.close();
                check(fixture.output.detaches == 1, "borrowed Surface was not detached exactly once");
                fixture.surfaceChanged(null,0,0);
                check(fixture.output.detaches == 1, "old Surface callback detached a later borrower");
                var owned = new Fixture(); owned.ownsOutput = true; owned.close();
                check(owned.output.detaches == 0, "owned output closure is delegated to content");
            }
            """);
    }
}
