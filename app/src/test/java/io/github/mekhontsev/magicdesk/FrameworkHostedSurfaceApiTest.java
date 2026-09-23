package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class FrameworkHostedSurfaceApiTest {
    @Test public void invalidSurfacesNeverSubmitTransactions() throws Exception {
        verify("""
                for (boolean targetInvalid : new boolean[]{false, true}) {
                    target.valid = !targetInvalid; anchor.valid = targetInvalid;
                    try { api.order(target, anchor); throw new AssertionError("invalid admitted"); }
                    catch (IllegalArgumentException expected) { }
                }
                check(created == 0, "preconditions changed compositor state");
                """);
    }

    @Test public void orderRequiresCommitAndPreservesAnchorOwnership() throws Exception {
        verify("""
                api.order(target, anchor);
                check(ordered == target && relative == anchor && layer == 1, "wrong ordering");
                check(created == 1 && applied == 1 && closed == 1 && acknowledged == 1, "commit lifetime");
                """);
    }

    @Test public void rejectedOrInterruptedOrderingReleasesTransaction() throws Exception {
        verify("""
                reject = true;
                try { api.order(target, anchor); throw new AssertionError("rejection hidden"); }
                catch (InvocationTargetException expected) {
                    check(expected.getCause() instanceof SecurityException, "wrong rejection");
                }
                check(applied == 0 && closed == 1, "rejected transaction leaked");
                reject = false; acknowledge = false;
                Thread.currentThread().interrupt();
                try { api.order(target, anchor); throw new AssertionError("missing commit accepted"); }
                catch (IllegalStateException expected) {
                    check(expected.getCause() instanceof InterruptedException, "wrong interruption");
                    check(Thread.currentThread().isInterrupted(), "interruption lost");
                } finally { Thread.interrupted(); }
                check(applied == 1 && closed == 2, "interrupted transaction leaked");
                """);
    }

    private static void verify(String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static int created, applied, closed, acknowledged, layer;
                static boolean reject, acknowledge = true;
                static SurfaceControl ordered, relative;
                public static class SurfaceControl {
                    boolean valid = true;
                    boolean isValid() { return valid; }
                    public static class Transaction implements AutoCloseable {
                        Runnable callback;
                        Transaction() { created++; }
                        public void setRelativeLayer(SurfaceControl target, SurfaceControl anchor, int z) {
                            if (reject) throw new SecurityException("denied");
                            ordered = target; relative = anchor; layer = z;
                        }
                        void addTransactionCommittedListener(java.util.concurrent.Executor executor, Runnable listener) {
                            callback = () -> executor.execute(listener);
                        }
                        void apply() { applied++; if (acknowledge) { acknowledged++; callback.run(); } }
                        public void close() { closed++; }
                    }
                }
                static class EventDrivenWaits {
                    enum Reason { WINDOW_TRANSITION_COMMIT }
                    static void noteFrameworkWait(Reason reason) { }
                }
                final Method relativeLayer = SurfaceControl.Transaction.class.getMethod(
                        "setRelativeLayer", SurfaceControl.class, SurfaceControl.class, int.class);
                Fixture() throws Exception { }
                public static void verify() throws Exception {
                    var api = new Fixture();
                    var target = new SurfaceControl();
                    var anchor = new SurfaceControl();
                """ + scenario + "}\n" + RuntimeSourceFixture.methods("FrameworkHostedSurfaceApi", "order"));
    }
}
