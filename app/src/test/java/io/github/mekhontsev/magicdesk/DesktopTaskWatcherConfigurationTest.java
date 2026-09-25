package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopTaskWatcherConfigurationTest {
    @Test public void configurationReturnsBeforeBinderAndCapturesInputs() throws Exception {
        verify("""
                Rect display = new Rect(100), work = new Rect(90);
                check(watcher.configure(4, display, work, 7, true), "not queued");
                check(calls.isEmpty(), "configuration blocked the caller in Binder");
                display.value = 200; work.value = 180;
                DesktopCompatibilitySettings.current = new DesktopCompatibilityPolicy(2);
                watcher.mExecutor.drain();
                check(calls.equals(List.of("configure:4:100:90:7:1", "protect:true")),
                        "configuration did not retain its snapshot and protection order: " + calls);
                """);
    }

    @Test public void newerConfigurationSupersedesQueuedCleanupAndGeometry() throws Exception {
        verify("""
                watcher.configure(4, new Rect(100), new Rect(90), 7, true);
                watcher.clearConfiguration(4);
                watcher.configure(0, new Rect(200), new Rect(180), 8, false);
                watcher.mExecutor.drain();
                check(calls.equals(List.of("configure:0:200:180:8:1", "protect:false")),
                        "stale configuration or cleanup executed: " + calls);
                """);
    }

    @Test public void cleanupAndDisconnectCancelQueuedConfiguration() throws Exception {
        verify("""
                watcher.configure(4, new Rect(100), new Rect(90), 7, true);
                watcher.clearConfiguration(4);
                watcher.mExecutor.drain();
                check(calls.equals(List.of("clear:4")), "cleanup did not supersede configuration");
                calls.clear();
                watcher.configure(4, new Rect(100), new Rect(90), 7, true);
                watcher.mConfigurationOperations.invalidate();
                watcher.mExecutor.drain();
                check(calls.isEmpty(), "disconnected observer was configured");
                """);
    }

    @Test public void closeDisablesProtectionBeforeParkingWithoutQueuedReenable() throws Exception {
        verify("""
                watcher.configure(4, new Rect(100), new Rect(90), 7, true);
                check(watcher.disableExternalTaskMigrationProtection(), "disable rejected");
                check(calls.equals(List.of("protect:false")), "close returned before disabling");
                watcher.mExecutor.drain();
                check(calls.equals(List.of("protect:false")), "queued configuration revived protection");
                """);
    }

    @Test public void executionFailureIsReportedAndCanBeRetried() throws Exception {
        verify("""
                watcher.mHandle.fail = true;
                check(watcher.configure(4, new Rect(100), new Rect(90), 7, true), "not queued");
                watcher.mExecutor.drain();
                check(failures == 1 && calls.size() == 1, "failure lost or protection applied after failure");
                watcher.mHandle.fail = false;
                watcher.configure(4, new Rect(100), new Rect(90), 7, true);
                watcher.mExecutor.drain();
                check(calls.size() == 3 && calls.get(2).equals("protect:true"), "retry failed");
                """);
    }

    @Test public void absentObserverAndStoppedExecutorRejectSubmission() throws Exception {
        verify("""
                watcher.mExecutor.reject = true;
                check(!watcher.configure(4, new Rect(100), new Rect(90), 7, true), "stopped executor accepted");
                watcher.mHandle = null;
                check(!watcher.configure(4, new Rect(100), new Rect(90), 7, true), "absent observer accepted");
                check(calls.isEmpty(), "rejected configuration reached Binder");
                """);
    }

    private static void verify(String scenario) throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static final String TAG = "fixture";
                static final List<String> calls = new ArrayList<>();
                static int failures;
                static class Rect {
                    int value;
                    Rect(int value) { this.value = value; }
                    Rect(Rect other) { value = other.value; }
                }
                record DesktopCompatibilityPolicy(int bits) { }
                static class DesktopCompatibilitySettings {
                    static DesktopCompatibilityPolicy current = new DesktopCompatibilityPolicy(1);
                    static DesktopCompatibilityPolicy current() { return current; }
                }
                static class Log { static void w(String tag, String message, Throwable error) { } }
                static void recordFailure(String code, String title, String detail, Throwable error) {
                    check(code.equals("TASK-OBSERVER-CONFIGURE-001"), "wrong diagnostic");
                    failures++;
                }
                static class Queue {
                    boolean reject;
                    final java.util.Queue<Runnable> pending = new ArrayDeque<>();
                    void execute(Runnable job) {
                        if (reject) throw new RejectedExecutionException();
                        pending.add(job);
                    }
                    void drain() { while (!pending.isEmpty()) pending.remove().run(); }
                }
                static class ShellTaskObserverHandle {
                    boolean fail;
                    void configure(int display, Rect bounds, Rect work, int host,
                            DesktopCompatibilityPolicy policy) throws IOException {
                        calls.add("configure:" + display + ":" + bounds.value + ":" + work.value
                                + ":" + host + ":" + policy.bits());
                        if (fail) throw new IOException("fixture configuration failure");
                    }
                    void setExternalTaskMigrationProtection(boolean enabled) throws IOException {
                        calls.add("protect:" + enabled);
                    }
                    void clearConfiguration(int display) throws IOException { calls.add("clear:" + display); }
                }
                final Queue mExecutor = new Queue();
                final LatestOperationSerializer mConfigurationOperations = new LatestOperationSerializer();
                ShellTaskObserverHandle mHandle = new ShellTaskObserverHandle();
                public static void verify() throws Exception {
                    Fixture watcher = new Fixture();
                """ + scenario + "}\n" + RuntimeSourceFixture.methods("DesktopTaskWatcher",
                        "configure", "clearConfiguration", "disableExternalTaskMigrationProtection"),
                "LatestOperationSerializer");
    }
}
