package io.github.mekhontsev.magicdesk;

import org.junit.Test;

/** The queued production methods run against deterministic Binder and pointer doubles. */
public final class DisplayInputSessionLifecycleTest {
    @Test public void closeCompletesAfterInFlightAcquisitionAndRestoration() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() throws Exception {
                    Fixture f = new Fixture();
                    f.reconcile(7, true);
                    check(ShellAccess.entered.await(2, TimeUnit.SECONDS), "acquisition not reached");
                    CountDownLatch complete = new CountDownLatch(1);
                    f.stop(complete::countDown);
                    check(complete.getCount() == 1, "Close overtook acquisition");
                    ShellAccess.proceed.countDown();
                    check(complete.await(2, TimeUnit.SECONDS), "Close did not complete");
                    check(!f.isRoutingReady(7) && !ShellAccess.route.active,
                            "late startup acquired a closing display");
                    check(!f.mMouse.active, "pointer survived Close");
                    f.mWorker.shutdown();
                    check(f.mWorker.awaitTermination(2, TimeUnit.SECONDS), "worker leaked");
                }
                """);
    }

    @Test public void reopenSameDisplayCannotPublishAnOldReadyCallback() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() throws Exception {
                    Fixture f = new Fixture();
                    f.reconcile(7, true);
                    check(ShellAccess.entered.await(2, TimeUnit.SECONDS), "acquisition not reached");
                    f.stop(() -> {});
                    f.reconcile(7, true);
                    ShellAccess.proceed.countDown();
                    f.mWorker.submit(() -> {}).get(2, TimeUnit.SECONDS);
                    check(f.isRoutingReady(7) && f.mMouse.starts == 1,
                            "old generation started the pointer");
                    CountDownLatch complete = new CountDownLatch(1);
                    f.stop(complete::countDown);
                    check(complete.await(2, TimeUnit.SECONDS), "Close did not complete");
                    f.mWorker.shutdown();
                    check(f.mWorker.awaitTermination(2, TimeUnit.SECONDS), "worker leaked");
                }
                """);
    }

    @Test public void failedRestorationCannotBeReplacedByAnotherLease() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() throws Exception {
                    Fixture f = new Fixture();
                    f.mRouting = new ShellInputRoutingHandle(7);
                    f.mRouting.fail = true;
                    ShellAccess.proceed.countDown();
                    f.reconcile(8, false);
                    f.mWorker.submit(() -> {}).get(2, TimeUnit.SECONDS);
                    check(ShellAccess.opens == 0 && !f.isRoutingReady(8),
                            "new lease overwrote failed cleanup");
                    f.mRouting.fail = false;
                    CountDownLatch complete = new CountDownLatch(1);
                    f.stop(complete::countDown);
                    check(complete.await(2, TimeUnit.SECONDS), "cleanup retry failed");
                    f.mWorker.shutdown();
                    check(f.mWorker.awaitTermination(2, TimeUnit.SECONDS), "worker leaked");
                }
                """);
    }

    @Test public void keyboardPlacementDoesNotRestartInputAndUsesLatestPreference() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() throws Exception {
                    Fixture f = new Fixture();
                    f.reconcile(7, true);
                    check(ShellAccess.entered.await(2, TimeUnit.SECONDS), "acquisition not reached");
                    f.setKeyboardOnAppDisplay(true, error -> { throw new AssertionError(error); });
                    ShellAccess.proceed.countDown();
                    f.mWorker.submit(() -> {}).get(2, TimeUnit.SECONDS);
                    check(ShellAccess.route.keyboardOnAppDisplay, "queued mode was lost");
                    f.setKeyboardOnAppDisplay(false, error -> { throw new AssertionError(error); });
                    f.mWorker.submit(() -> {}).get(2, TimeUnit.SECONDS);
                    check(!ShellAccess.route.keyboardOnAppDisplay, "live switch ignored");
                    check(ShellAccess.opens == 1 && f.mMouse.starts == 1 && f.isRoutingReady(7),
                            "keyboard mode restarted input");
                    f.stop(() -> {});
                    f.mWorker.shutdown();
                    check(f.mWorker.awaitTermination(2, TimeUnit.SECONDS), "worker leaked");
                }
                """);
    }

    @Test public void failedKeyboardSwitchRetainsWorkingPointer() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() throws Exception {
                    Fixture f = new Fixture();
                    ShellAccess.proceed.countDown();
                    f.reconcile(7, true);
                    f.mWorker.submit(() -> {}).get(2, TimeUnit.SECONDS);
                    ShellAccess.route.fail = true;
                    CountDownLatch failed = new CountDownLatch(1);
                    f.setKeyboardOnAppDisplay(true, error -> failed.countDown());
                    check(failed.await(2, TimeUnit.SECONDS), "failure not reported");
                    check(f.isRoutingReady(7) && f.mMouse.active && ShellAccess.route.active,
                            "keyboard failure dropped working input");
                    ShellAccess.route.fail = false;
                    f.stop(() -> {});
                    f.mWorker.shutdown();
                    check(f.mWorker.awaitTermination(2, TimeUnit.SECONDS), "worker leaked");
                }
                """);
    }

    private static String fixture() throws Exception {
        return """
                static class Display { static final int INVALID_DISPLAY = -1, DEFAULT_DISPLAY = 0; }
                static class Handler { void post(Runnable action) { action.run(); } }
                final Handler mHandler = new Handler();
                final Runnable mChanged = () -> {};
                final Mouse mMouse = new Mouse();
                final ExecutorService mWorker = Executors.newSingleThreadExecutor();
                volatile int mRequestedDisplay = -1, mReadyDisplay = -1;
                volatile long mGeneration;
                boolean mDestroyed, mDesktopShortcuts, mTransitioning;
                volatile boolean mKeyboardOnAppDisplay;
                String mError = "";
                ShellInputRoutingHandle mRouting;
                static class Mouse {
                    volatile boolean active;
                    int starts;
                    void start() { active = true; starts++; }
                    void stop() { active = false; }
                }
                static class DesktopShortcutService { static void setTargetDisplay(int id) {} }
                static class CompatibilityDiagnostics {
                    static void record(String code, String title, String detail, Throwable error) {}
                }
                static class InputSessionDiagnostics {
                    static void noteAttempt(int id) {}
                    static void noteReady() {}
                }
                static class ShellInputRoutingHandle {
                    final int id;
                    volatile boolean active = true, fail;
                    boolean keyboardOnAppDisplay;
                    ShellInputRoutingHandle(int id) { this.id = id; }
                    int displayId() { return id; }
                    void setKeyboardPlacement(boolean enabled) throws IOException {
                        if (fail) throw new IOException("policy failed");
                        keyboardOnAppDisplay = enabled;
                    }
                    void close() throws IOException {
                        if (fail) throw new IOException("restore failed");
                        active = false;
                    }
                }
                static class ShellAccess {
                    static final CountDownLatch entered = new CountDownLatch(1);
                    static final CountDownLatch proceed = new CountDownLatch(1);
                    static ShellInputRoutingHandle route;
                    static int opens;
                    static ShellInputRoutingHandle openInputRouting(int id, boolean shortcuts, boolean keyboard) throws IOException {
                        opens++;
                        entered.countDown();
                        try { check(proceed.await(2, TimeUnit.SECONDS), "fixture gate timed out"); }
                        catch (InterruptedException e) { throw new IOException(e); }
                        route = new ShellInputRoutingHandle(id);
                        route.keyboardOnAppDisplay = keyboard;
                        return route;
                    }
                    static void cleanupInputRouting() throws IOException {}
                    static boolean isReady() { return true; }
                    static String usefulMessage(Throwable error) { return error.getMessage(); }
                }
                static void report(String code, String title, IOException error) {}
                """ + RuntimeSourceFixture.methods("DisplayInputSession",
                        "reconcile", "stop", "release", "isRoutingReady", "setKeyboardOnAppDisplay");
    }
}
