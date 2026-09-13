package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DisplayViewerConnectionTest {
    @Test public void deadLeaseInvalidatesOnceAndStaleFailuresCannotInvalidateNewBinding() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Main {
                    Deque<Runnable> queue = new ArrayDeque<>();
                    void post(Runnable r) { queue.add(r); }
                    void drain() { while (!queue.isEmpty()) queue.remove().run(); }
                }
                static final Main MAIN = new Main();
                interface IDisplayViewer {
                    IDisplayViewer asBinder();
                    void unlinkToDeath(Object recipient, int flags);
                    void close() throws Exception;
                }
                static class Lease implements IDisplayViewer {
                    int unlinked, closed;
                    public IDisplayViewer asBinder() { return this; }
                    public void unlinkToDeath(Object recipient, int flags) { unlinked++; }
                    public void close() { closed++; }
                }
                IDisplayViewer mLease;
                Object mDeathRecipient;
                long mGeneration = 1;
                final List<Throwable> failures = new ArrayList<>();
                final java.util.function.Consumer<Throwable> mFailure = failures::add;
                static void report(Exception error) {}
                public static void verify() throws Exception {
                    Fixture f = new Fixture();
                    Lease first = new Lease(); f.mLease = first; f.mDeathRecipient = new Object();
                    f.failed(1, new IOException("binder died"));
                    f.failed(1, new IOException("input failed")); MAIN.drain();
                    check(f.failures.size() == 1 && f.mGeneration == 2 && f.mLease == null,
                            "dead binding was not invalidated exactly once");
                    check(first.unlinked == 1 && first.closed == 1, "lease was not released exactly once");
                    Lease second = new Lease(); f.mLease = second; f.mGeneration = 3;
                    f.failed(1, new IOException("old binder death")); MAIN.drain();
                    check(f.failures.size() == 1 && f.mLease == second, "old death invalidated new binding");
                    f.failed(3, new IOException("failure in flight"));
                    f.mGeneration = 4; f.mLease = new Lease(); MAIN.drain();
                    check(f.failures.size() == 1 && f.mLease != null, "stale UI callback invalidated replacement");
                }
                """ + RuntimeSourceFixture.methods("DisplayViewerConnection", "failed", "release")
                        .replace("throws android.os.RemoteException", "throws Exception"));
    }
}
