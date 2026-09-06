package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class RuntimeRelayTeardownRegressionTest {
    @Test
    public void explicitStopClosesKeyboardBeforeRouting() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() throws Exception {
                    Fixture f = new Fixture();
                    ShellInputRoutingHandle routing = f.mInputRouting;
                    f.stopRouting();
                    check(!routing.releasedWhileKeyboardActive, "stop removed routing before keyboard exit");
                }
                """);
    }

    @Test
    public void supervisorFinallyClosesKeyboardBeforeRouting() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() throws Exception {
                    Fixture f = new Fixture();
                    ShellInputRoutingHandle routing = f.mInputRouting;
                    f.cleanupFinally(f.mKeyboardStream, routing);
                    check(!routing.releasedWhileKeyboardActive, "finally removed routing before keyboard exit");
                }
                """);
    }

    @Test
    public void concurrentStopAndFinallyCannotBypassAnInProgressKeyboardClose() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() throws Exception {
                    Fixture f = new Fixture();
                    ShellStreamHandle keyboard = f.mKeyboardStream;
                    ShellInputRoutingHandle routing = f.mInputRouting;
                    keyboard.block = true;
                    ExecutorService workers = Executors.newFixedThreadPool(2);
                    try {
                        Future<?> stop = workers.submit(f::stopRouting);
                        check(keyboard.entered.await(2, TimeUnit.SECONDS), "keyboard close was not reached");
                        Future<?> cleanup = workers.submit(() -> f.cleanupFinally(keyboard, routing));
                        try {
                            cleanup.get(100, TimeUnit.MILLISECONDS);
                            check(!routing.closed, "concurrent finally bypassed keyboard shutdown");
                        } catch (TimeoutException expected) {
                            check(!routing.closed, "routing closed during keyboard shutdown");
                        } finally {
                            keyboard.proceed.countDown();
                        }
                        stop.get(2, TimeUnit.SECONDS);
                        cleanup.get(2, TimeUnit.SECONDS);
                        check(routing.closed && !routing.releasedWhileKeyboardActive,
                                "concurrent cleanup removed routing before keyboard exit");
                    } finally {
                        keyboard.proceed.countDown();
                        workers.shutdownNow();
                        check(workers.awaitTermination(2, TimeUnit.SECONDS), "fixture worker leaked");
                    }
                }
                """);
    }

    private static String fixture() throws Exception {
        return """
                final Object mLock = new Object(), mTeardownLock = new Object();
                boolean mRoutingRequested=true, mRoutingReady=true, mFullShortcutMode=true, mHardwareKeyboard;
                int mRoutingDisplayId=7, mGeneration;
                Thread mSupervisorThread;
                ShellStreamHandle mKeyboardStream=new ShellStreamHandle();
                ShellInputRoutingHandle mInputRouting=new ShellInputRoutingHandle(mKeyboardStream);
                Mouse mMouseBridge=new Mouse();
                Runnable mStateChanged=() -> {};
                static class Display { static final int INVALID_DISPLAY=-1; }
                static class Mouse { void setCaptureEnabled(boolean enabled) {} }
                static class KeyboardShortcutWatcher { static void clearModifierState() {} }
                static class HardwareKeyboardLayoutController {
                    static void detachLayoutSink(Object sink) {}
                }
                static class ShellStreamHandle implements Closeable {
                    final java.util.concurrent.atomic.AtomicBoolean closed=new java.util.concurrent.atomic.AtomicBoolean();
                    final CountDownLatch entered=new CountDownLatch(1), proceed=new CountDownLatch(1);
                    volatile boolean active=true, block;
                    public void close() {
                        if (!closed.compareAndSet(false, true)) return;
                        entered.countDown();
                        if (block) {
                            try { check(proceed.await(2, TimeUnit.SECONDS), "fixture gate timed out"); }
                            catch (InterruptedException e) { throw new AssertionError(e); }
                        }
                        active=false;
                    }
                }
                static class ShellInputRoutingHandle implements Closeable {
                    final ShellStreamHandle keyboard;
                    volatile boolean closed, releasedWhileKeyboardActive;
                    ShellInputRoutingHandle(ShellStreamHandle stream) { keyboard=stream; }
                    public void close() { releasedWhileKeyboardActive |= keyboard.active; closed=true; }
                }
                boolean isCurrentGeneration(int generation) { return false; }
                boolean isActive(int generation) { return false; }
                void clearActiveHandles(ShellStreamHandle stream, ShellInputRoutingHandle routing, int gen) {}
                void cleanupFinally(ShellStreamHandle keyboardStream, ShellInputRoutingHandle inputRouting) {
                    Object layoutSink=null;
                    BufferedReader keyboardReader=null;
                    int generation=0;
                """ + RuntimeSourceFixture.finallyBlock("DesktopInputRelaySession", "runOnce") + "}\n"
                + RuntimeSourceFixture.methods("DesktopInputRelaySession",
                        "stopRouting", "closeQuietly", "closeRoutingHandles");
    }
}
