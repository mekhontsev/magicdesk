package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DeviceSetupAuditLifecycleTest {
    private static String fixture() throws Exception {
        return """
                boolean mBusy, mManual = true, unavailable;
                Object mSessionProfile = new Object();
                DeviceSetupManager.Audit mAudit;
                int renders, errors, starts;
                static final String TAG = "test";
                Object getApplicationContext() { return this; }
                boolean isActivityUnavailable() { return unavailable; }
                void runOnUiThread(Runnable callback) { callback.run(); }
                void setBusy(boolean busy, int status) { mBusy = busy; }
                void ensureSetupContent() {}
                void renderAudit(DeviceSetupManager.Audit audit) { renders++; }
                void showOperationError(Throwable error) { errors++; }
                void startMagicDesk() { starts++; }
                interface SetupOperation { DeviceSetupManager.Audit run() throws IOException; }
                static class R { static class string { static final int setup_status_checking = 1; } }
                static class Log { static void w(String tag, String message, Throwable error) {} }
                static class DesktopSetupStatus { static void refresh(Object context) {} }
                static class Thread {
                    static final ArrayDeque<Runnable> workers = new ArrayDeque<>();
                    final Runnable work;
                    Thread(Runnable work, String name) { this.work = work; }
                    void start() { workers.add(work); }
                    static void drain() { while (!workers.isEmpty()) workers.remove().run(); }
                }
                static class DeviceSetupManager {
                    static Audit result;
                    static RuntimeException failure;
                    static Audit audit(Object context, Object profile) {
                        if (failure != null) throw failure;
                        return result;
                    }
                    static class Audit {
                        final boolean ready;
                        Audit(boolean ready) { this.ready = ready; }
                        boolean canEnterMagicDesk() { return ready; }
                    }
                }
                """ + RuntimeSourceFixture.methods("DeviceSetupActivity",
                "runAudit", "showAuditFailure", "runOperation");
    }

    @Test public void manualAuditOnlyRendersReadiness() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    for (boolean ready : new boolean[] {true, false}) {
                        Fixture f = new Fixture();
                        DeviceSetupManager.result = new DeviceSetupManager.Audit(ready);
                        f.runAudit();
                        f.runAudit();
                        check(Thread.workers.size() == 1, "duplicate audit started");
                        Thread.drain();
                        check(f.mAudit == DeviceSetupManager.result && !f.mBusy,
                                "audit result was not published");
                        check(f.renders == 1 && f.starts == 0 && f.errors == 0,
                                "manual audit changed more than the setup UI");
                    }
                }
                """);
    }

    @Test public void failedAuditAndOperationOnlyReportErrors() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    DeviceSetupManager.failure = new IllegalStateException("access unavailable");
                    Fixture f = new Fixture();
                    f.runAudit();
                    Thread.drain();
                    check(!f.mBusy && f.errors == 1 && f.starts == 0 && f.renders == 0,
                            "failed audit changed runtime or left UI busy");
                    f.runOperation(1, () -> { throw new IOException("operation failed"); });
                    Thread.drain();
                    check(!f.mBusy && f.errors == 3 && f.starts == 0,
                            "operation failure did not report and recheck readiness");
                    DeviceSetupManager.failure = null;
                    for (boolean ready : new boolean[] {true, false}) {
                        DeviceSetupManager.result = new DeviceSetupManager.Audit(ready);
                        f.runOperation(1, () -> DeviceSetupManager.result);
                        Thread.drain();
                        check(!f.mBusy && f.mAudit == DeviceSetupManager.result,
                                "operation result was not rendered");
                    }
                    check(f.renders == 2 && f.starts == 0, "operation result launched Desktop");
                }
                """);
    }

    @Test public void startupContinuationRequiresReadyAuditAndLiveActivity() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() {
                    Fixture f = new Fixture();
                    f.mManual = false;
                    DeviceSetupManager.result = new DeviceSetupManager.Audit(false);
                    f.runAudit();
                    Thread.drain();
                    check(f.starts == 0 && f.renders == 1, "unready startup continued");
                    DeviceSetupManager.result = new DeviceSetupManager.Audit(true);
                    f.runAudit();
                    Thread.drain();
                    check(f.starts == 1 && !f.mBusy, "ready startup did not continue");
                    f.runAudit();
                    f.unavailable = true;
                    Thread.drain();
                    f.showAuditFailure(new IOException());
                    check(f.starts == 1 && f.renders == 1 && f.errors == 0,
                            "destroyed Activity handled an audit callback");
                }
                """);
    }
}
