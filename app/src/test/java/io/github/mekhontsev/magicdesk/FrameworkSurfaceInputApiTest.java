package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class FrameworkSurfaceInputApiTest {
    @Test public void permissionAndOwnershipPrecedeTransactions() throws Exception {
        verify("""
                context.granted = false;
                try { api.trustOwnedOverlay(context, surface); throw new AssertionError("denied accepted"); }
                catch (SecurityException expected) { }
                context.granted = true;
                surface.valid = false;
                try { api.trustOwnedOverlay(context, surface); throw new AssertionError("invalid accepted"); }
                catch (IllegalArgumentException expected) { }
                try { api.trustOwnedOverlay(context, new Object()); throw new AssertionError("wrong type accepted"); }
                catch (IllegalArgumentException expected) { }
                check(created == 0 && applied == 0, "precondition mutated SurfaceFlinger");
                """);
    }

    @Test public void ownedGrantWaitsForCommitAndReleasesTransaction() throws Exception {
        verify("""
                api.trustOwnedOverlay(context, surface);
                check(target == surface && trusted, "grant changed another surface");
                check(created == 1 && applied == 1 && closed == 1, "transaction lifetime");
                check(acknowledged == 1, "commit not acknowledged");
                """);
    }

    @Test public void failedOrInterruptedGrantDoesNotReportSuccess() throws Exception {
        verify("""
                reject = true;
                try { api.trustOwnedOverlay(context, surface); throw new AssertionError("rejection hidden"); }
                catch (InvocationTargetException expected) {
                    check(expected.getCause() instanceof SecurityException, "wrong failure");
                }
                check(applied == 0 && closed == 1, "failed transaction retained or applied");
                reject = false;
                acknowledge = false;
                Thread.currentThread().interrupt();
                try { api.trustOwnedOverlay(context, surface); throw new AssertionError("missing ack accepted"); }
                catch (IllegalStateException expected) {
                    check(expected.getCause() instanceof InterruptedException, "wrong interruption");
                    check(Thread.currentThread().isInterrupted(), "interruption lost");
                } finally { Thread.interrupted(); }
                check(applied == 1 && closed == 2, "interrupted transaction retained");
                """);
    }

    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static final String PERMISSION = "android.permission.ACCESS_SURFACE_FLINGER";
                static int created, applied, closed, acknowledged;
                static boolean trusted, reject, acknowledge = true;
                static SurfaceControl target;
                static class Process {
                    static int myPid() { return 123; }
                    static int myUid() { return 2000; }
                }
                static class PackageManager { static final int PERMISSION_GRANTED = 0; }
                static class Context {
                    boolean granted = true;
                    int checkPermission(String permission, int pid, int uid) {
                        check(permission.equals(PERMISSION) && pid == 123 && uid == 2000,
                                "permission checked for Binder caller instead of service identity");
                        return granted ? 0 : -1;
                    }
                }
                public static class SurfaceControl {
                    boolean valid = true;
                    boolean isValid() { return valid; }
                    public static class Transaction implements AutoCloseable {
                        Runnable callback;
                        Transaction() { created++; }
                        public void setTrustedOverlay(SurfaceControl surface, boolean trust) {
                            if (reject) throw new SecurityException("denied");
                            target = surface; trusted = trust;
                        }
                        void addTransactionCommittedListener(java.util.concurrent.Executor executor, Runnable listener) {
                            callback = () -> executor.execute(listener);
                        }
                        void apply() {
                            applied++;
                            if (acknowledge) { acknowledged++; callback.run(); }
                        }
                        public void close() { closed++; }
                    }
                }
                final Method mSetTrustedOverlay = SurfaceControl.Transaction.class.getMethod(
                        "setTrustedOverlay", SurfaceControl.class, boolean.class);
                Fixture() throws Exception { }
                public static void verify() throws Exception {
                    final var api = new Fixture();
                    final var context = new Context();
                    final var surface = new SurfaceControl();
                """ + scenario + "}\n"
                + RuntimeSourceFixture.methods("FrameworkSurfaceInputApi", "trustOwnedOverlay"));
    }
}
