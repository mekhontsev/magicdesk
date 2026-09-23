package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class ShellInputRegionReceiptTest {
    @Test public void readyJoinsRegionAndDispatcherThenReleasesResources() throws Exception {
        verify("""
                Handler.drain();
                observed.accept(null);
                Handler.drain();
                check(unregistered == 1 && replies == 0 && reported != null, "region alone admitted input");
                reported.run(); Handler.drain();
                check(replies == 1 && error == null && unlinks == 1 && Handler.deadline == null, "receipt cleanup");
                request.cancel(); reported.run(); Handler.drain();
                check(replies == 1 && unregistered == 1, "duplicate receipt/cleanup");
                """);
    }

    @Test public void cancellationBeforeRegistrationDuringObservationAndAfterMatchIsFinal() throws Exception {
        for (int phase = 0; phase < 3; phase++) {
            verify("""
                    if (PHASE > 0) Handler.drain();
                    if (PHASE > 1) { observed.accept(null); Handler.drain(); }
                    request.cancel(); Handler.drain();
                    if (observed != null) observed.accept(null);
                    if (reported != null) reported.run();
                    Handler.drain();
                    check(replies == 0 && registered == unregistered && links == unlinks,
                            "cancellation leaked or emitted success");
                    check(Handler.deadline == null, "cancelled deadline retained");
                    """.replace("PHASE", Integer.toString(phase)));
        }
    }

    @Test public void deadlineAndOwnerDeathCannotAuthorizeInput() throws Exception {
        verify("""
                Handler.drain();
                Handler.deadline.run(); Handler.drain();
                check(replies == 1 && error != null && unregistered == 1, "timeout admitted input");
                observed.accept(null); Handler.drain();
                check(reported == null && replies == 1, "late match revived timeout");
                """);
        verify("""
                Handler.drain(); owner.death.binderDied(); Handler.drain();
                check(replies == 0 && unregistered == 1 && unlinks == 1, "dead owner retained subscription");
                """);
    }

    @Test public void observationAndSubmissionFailureReleaseEverything() throws Exception {
        verify("""
                Handler.drain(); observed.accept(new IOException("unavailable")); Handler.drain();
                check(replies == 1 && error != null && unregistered == 1 && unlinks == 1,
                        "observation error hidden");
                """);
        verify("""
                failSubmit = true;
                Handler.drain(); observed.accept(null); Handler.drain();
                check(replies == 1 && error != null && unregistered == 1 && unlinks == 1,
                        "submission error hidden");
                """);
    }

    private static void verify(String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static int registered, unregistered, links, unlinks, replies;
                static String error;
                static boolean failSubmit;
                static Runnable reported;
                static java.util.function.Consumer<Throwable> observed;
                static class Region { Region(Region other) { } }
                static class RemoteException extends Exception { }
                static class IBinder {
                    interface DeathRecipient { void binderDied(); }
                    DeathRecipient death;
                    void linkToDeath(DeathRecipient callback, int flags) throws RemoteException { links++; death = callback; }
                    boolean unlinkToDeath(DeathRecipient callback, int flags) { unlinks++; death = null; return true; }
                }
                static IBinder owner = new IBinder();
                static class IInputRegionCallback {
                    IBinder asBinder() { return owner; }
                    void completed(String failure) throws RemoteException { replies++; error = failure; }
                }
                static class IInputRegionReceipt { abstract static class Stub { public abstract void cancel(); } }
                static class Looper { static Object getMainLooper() { return null; } }
                static class Handler {
                    static ArrayDeque<Runnable> queue = new ArrayDeque<>();
                    static Runnable deadline;
                    Handler(Object looper) { }
                    void post(Runnable action) { queue.add(action); }
                    void postDelayed(Runnable action, long millis) { deadline = action; }
                    void removeCallbacks(Runnable action) { if (deadline == action) deadline = null; }
                    static void drain() { while (!queue.isEmpty()) queue.remove().run(); }
                }
                static class FrameworkInputWindowObservationSource {
                    static Closeable observeTouchableRegion(IBinder window, int display, Region region,
                            java.util.function.Consumer<Throwable> callback) throws ReflectiveOperationException {
                        observed = callback; registered++; return () -> unregistered++;
                    }
                }
                static class FrameworkSurfaceInputApi {
                    static void reportInputWindows(Runnable callback) throws ReflectiveOperationException {
                        if (failSubmit) throw new IllegalStateException("unavailable");
                        reported = callback;
                    }
                }
                static class EventDrivenWaits {
                    enum Reason { INPUT_WINDOW_COMMIT }
                    static void noteFrameworkWait(Reason reason) { }
                }
                static class Log { static void w(String tag, String message, Throwable error) { } }
                public static void verify() throws Exception {
                    var request = new Fixture().new ShellInputRegionReceipt(new IBinder(), 7,
                            new Region(null), new IInputRegionCallback());
                """ + scenario + "}\n"
                + RuntimeSourceFixture.nestedClass("ShellInputRegionReceipt", "ShellInputRegionReceipt"));
    }
}
