package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DisplayViewerLeaseTest {
    @Test public void parkCancelsHeldInputAndReleasesResourcesEvenAfterInjectionFailure() throws Exception {
        RuntimeSourceFixture.verify("""
                boolean mClosed;
                MotionEvent mTouch = new MotionEvent();
                final Map<Integer, KeyEvent> mKeys = new LinkedHashMap<>();
                final Display mPresentation = new Display();
                Surface mSurface = new Surface();
                final Owner mOwner = new Owner();
                final Object mDeath = new Object();
                final List<Integer> actions = new ArrayList<>();
                boolean failInput;
                static class MotionEvent {
                    static final int ACTION_CANCEL = 3;
                    int action;
                    boolean recycled;
                    void setAction(int value) { action = value; }
                    void recycle() { recycled = true; }
                }
                static class KeyEvent {
                    static final int ACTION_UP = 1, FLAG_CANCELED = 32;
                    int action;
                    int getFlags() { return 0; }
                    static KeyEvent changeAction(KeyEvent event, int action) {
                        KeyEvent result = new KeyEvent(); result.action = action; return result;
                    }
                    static KeyEvent changeTimeRepeat(KeyEvent event, long time, int repeat, int flags) {
                        check((flags & FLAG_CANCELED) != 0, "key release not cancelled"); return event;
                    }
                }
                static class SystemClock { static long uptimeMillis() { return 100; } }
                static class Display { int parked; void close() { parked++; } }
                static class Surface { boolean released; void release() { released = true; } }
                static class Owner { int unlinked; void unlinkToDeath(Object death, int flags) { unlinked++; } }
                void inject(MotionEvent event) {
                    actions.add(event.action);
                    if (failInput) throw new IllegalStateException("source removed");
                }
                void inject(KeyEvent event) {
                    actions.add(event.action);
                    if (failInput) throw new IllegalStateException("source removed");
                }
                public static void verify() {
                    for (boolean failure : new boolean[]{false, true}) {
                        Fixture f = new Fixture();
                        f.failInput = failure;
                        f.mKeys.put(1, new KeyEvent());
                        f.mKeys.put(2, new KeyEvent());
                        MotionEvent touch = f.mTouch;
                        Surface surface = f.mSurface;
                        boolean thrown = false;
                        try { f.close(); } catch (IllegalStateException expected) { thrown = true; }
                        check(thrown == failure, "input error was lost");
                        check(f.actions.equals(List.of(3, 1, 1)), "not all held input was released");
                        check(f.mPresentation.parked == 1 && surface.released && touch.recycled,
                                "input failure skipped Surface cleanup");
                        check(f.mOwner.unlinked == 1 && f.mKeys.isEmpty(), "lease retained resources");
                        f.close();
                        check(f.mPresentation.parked == 1, "second close touched parked source");
                    }
                }
                """ + RuntimeSourceFixture.methods("ShellDisplayViewer", "close"));
    }
}
