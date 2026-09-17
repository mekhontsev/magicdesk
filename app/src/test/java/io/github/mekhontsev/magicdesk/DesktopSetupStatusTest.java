package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public final class DesktopSetupStatusTest {
    @Test public void readinessRequiresInspectedSettingsAndCompletedRestart() {
        assertEquals(DesktopSetupStatus.State.READY, DesktopSetupStatus.evaluate(true, true, false));
        assertEquals(DesktopSetupStatus.State.RESTART_REQUIRED, DesktopSetupStatus.evaluate(true, true, true));
        assertEquals(DesktopSetupStatus.State.SETUP_REQUIRED, DesktopSetupStatus.evaluate(true, false, false));
        assertEquals(DesktopSetupStatus.State.SETUP_REQUIRED, DesktopSetupStatus.evaluate(true, false, true));
        assertEquals(DesktopSetupStatus.State.UNKNOWN, DesktopSetupStatus.evaluate(false, true, false));
        assertEquals(DesktopSetupStatus.State.UNKNOWN, DesktopSetupStatus.evaluate(false, false, false));
    }

    @Test public void readOnlyChecksFollowAccessAndCoalesceRefreshWithoutLosingEvents() throws Exception {
        final String status = RuntimeSourceFixture.nestedClass("DesktopSetupStatus", "DesktopSetupStatus")
                .replace("final class DesktopSetupStatus", "static final class DesktopSetupStatus")
                .replace("android.os.Build.VERSION.SDK_INT", "sdk");
        RuntimeSourceFixture.verify("""
                static int sdk = 34;
                static final ArrayDeque<Runnable> workers = new ArrayDeque<>();
                static class Context { Context getApplicationContext() { return this; } }
                static class Thread {
                    final Runnable run;
                    Thread(Runnable run, String name) { this.run = run; }
                    void start() { workers.add(run); }
                }
                static class RuntimeCapabilities { static boolean supportsDesktop(int value) { return value >= 35; } }
                static class ShellAccess {
                    static boolean ready;
                    static java.util.function.Consumer<Access> listener;
                    record Access(boolean isReady) { }
                    static boolean isReady() { return ready; }
                    static String usefulMessage(Throwable error) { return error.getMessage(); }
                    static void addStateListener(java.util.function.Consumer<Access> value) {
                        listener = value; listener.accept(new Access(ready));
                    }
                    static void emit(boolean value) { ready = value; listener.accept(new Access(value)); }
                }
                static class DeviceSetupManager {
                    static int reads;
                    static Runnable duringRead;
                    static RuntimeException failure;
                    static Audit result = new Audit();
                    static class Audit {
                        boolean shellReady = true, configurationReady = true, rebootRequired;
                        String runtimeError = "";
                    }
                    static Audit audit(Context context) {
                        reads++;
                        if (duringRead != null) { Runnable call = duringRead; duringRead = null; call.run(); }
                        if (failure != null) throw failure;
                        return result;
                    }
                }
                """ + status + """
                public static void verify() {
                    Context app = new Context();
                    DesktopSetupStatus.initialize(app);
                    check(ShellAccess.listener == null && workers.isEmpty(), "API 34 started a Desktop audit");
                    sdk = 35;
                    DesktopSetupStatus.initialize(app);
                    check(workers.isEmpty(), "audit requested access before the service was ready");
                    ShellAccess.emit(true);
                    DesktopSetupStatus.refresh(app);
                    DesktopSetupStatus.refresh(app);
                    check(workers.size() == 1, "parallel setup checks");
                    workers.remove().run();
                    check(DesktopSetupStatus.current().state() == DesktopSetupStatus.State.READY, "configured device not ready");
                    check(workers.size() == 1, "refresh during read was dropped");
                    workers.remove().run();
                    check(workers.isEmpty() && DeviceSetupManager.reads == 2, "checks keep polling");
                    DesktopSetupStatus.refresh(app);
                    DeviceSetupManager.duringRead = () -> ShellAccess.emit(false);
                    workers.remove().run();
                    check(DesktopSetupStatus.current().audit() == null, "stale audit survived access loss");
                    check(workers.isEmpty(), "lost access still starts audits");
                    DeviceSetupManager.result.rebootRequired = true;
                    ShellAccess.emit(true);
                    workers.remove().run();
                    check(DesktopSetupStatus.current().state() == DesktopSetupStatus.State.RESTART_REQUIRED, "restart was ignored");
                    DeviceSetupManager.failure = new IllegalStateException("read failed");
                    DesktopSetupStatus.refresh(app);
                    workers.remove().run();
                    check(DesktopSetupStatus.current().state() == DesktopSetupStatus.State.UNKNOWN, "error became configured state");
                    check(DesktopSetupStatus.current().error().equals("read failed"), "failure detail was lost");
                    check(workers.isEmpty(), "failed checks keep polling");
                }
                """);
    }
}
