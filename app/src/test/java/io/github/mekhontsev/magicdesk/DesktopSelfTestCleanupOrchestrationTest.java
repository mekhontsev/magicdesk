package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopSelfTestCleanupOrchestrationTest {
    @Test public void fixturesCloseBeforeProductionHomePresentation() throws Exception {
        verify("""
                run(result, DesktopSelfTestTarget.PHONE, 0, null);
                check(events.indexOf("close-complete") >= 0, "production close completion was not awaited");
                check(events.indexOf("remove") < events.indexOf("close-request"), "fixtures outlived their desktop owner");
                check(events.indexOf("close-complete") < events.indexOf("verify-home"), "HOME checked before production close completed");
                check(Collections.frequency(events, "remove") == 1, "fixture removal repeated after HOME presentation");
                check(result.state == DesktopSelfTestResult.State.PASS, result.detail);
                """);
    }
    @Test public void fixtureFailureStillRunsProductionClose() throws Exception {
        verify("failRemoval = true; run(result, DesktopSelfTestTarget.PHONE, 0, null); check(events.contains(\"close-complete\"), \"failed fixture trapped HOME\"); check(result.state == DesktopSelfTestResult.State.FAIL, \"fixture failure lost\");");
    }
    @Test public void rejectedProductionCloseCannotReportPass() throws Exception {
        verify("closeSuccess = false; run(result, DesktopSelfTestTarget.PHONE, 0, null); check(result.state == DesktopSelfTestResult.State.FAIL, \"rejected close reported PASS\");");
    }
    @Test public void alreadyRemovedSessionIsNotClosedAgain() throws Exception {
        verify("DesktopRuntimeBridge.active = false; DesktopHomeRoleLease.lease = null; run(result, DesktopSelfTestTarget.SIMULATED, 7, new SimulatedDisplayLease()); check(!events.contains(\"close-request\"), \"removed session closed again\"); check(events.contains(\"lease-close\"), \"display lease retained\"); check(result.state == DesktopSelfTestResult.State.PASS, result.detail);");
    }
    @Test public void remainingHomeLeaseUsesProductionCloseAfterHostLoss() throws Exception {
        verify("DesktopRuntimeBridge.active = false; run(result, DesktopSelfTestTarget.PHONE, 0, null); check(events.contains(\"close-complete\"), \"orphaned lease bypassed production close\");");
    }
    @Test public void unavailableShellCannotReportCleanupPass() throws Exception {
        verify("ShellAccess.ready = false; run(result, DesktopSelfTestTarget.PHONE, -1, null); check(result.state == DesktopSelfTestResult.State.FAIL, \"unavailable shell reported PASS\");");
    }
    @Test public void shellLossDuringCleanupCannotReportPass() throws Exception {
        verify("ShellAccess.loseOnClose = true; run(result, DesktopSelfTestTarget.PHONE, 0, null); check(result.state == DesktopSelfTestResult.State.FAIL, \"shell loss reported PASS\");");
    }
    private static void verify(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static final List<String> events = new ArrayList<>();
                static boolean failRemoval, closeSuccess = true;
                static class DesktopSelfTestResult {
                    enum State { PASS, FAIL } State state; String detail;
                    void add(State value, String id, String title, String text) { state = value; detail = text; }
                }
                enum DesktopSelfTestTarget { PHONE, SIMULATED, WIRED }
                static class Display { static final int DEFAULT_DISPLAY = 0; }
                static class ShellAccess {
                    static boolean ready = true, loseOnClose;
                    static boolean isReady() { return ready; }
                    static String run(String command) throws IOException { if (!ready) throw new IOException("shell lost"); return "null"; }
                }
                static class DesktopDisplayTarget {}
                static class DesktopHomeRoleLease {
                    static class State { int displayId; DesktopDisplayTarget target() { return new DesktopDisplayTarget(); } }
                    static State lease = new State();
                    static State snapshot() { return lease; }
                }
                static Runnable pendingClose;
                static class DesktopRuntimeBridge {
                    static boolean active = true;
                    static DesktopDisplayTarget getDesktopTarget(int display) { return active ? new DesktopDisplayTarget() : null; }
                }
                enum DesktopCloseMode { HOME }
                static class DesktopOperations {
                    interface Callback { void complete(boolean success); }
                    static void closeDesktop(DesktopDisplayTarget target, DesktopCloseMode mode, Callback callback) {
                        check(mode == DesktopCloseMode.HOME, "wrong close destination");
                        events.add("close-request");
                        if (ShellAccess.loseOnClose) ShellAccess.ready = false;
                        pendingClose = () -> { events.add("close-complete"); callback.complete(closeSuccess); };
                    }
                }
                static class CountDownLatch {
                    int count; CountDownLatch(int count) { this.count = count; }
                    void countDown() { count--; }
                    boolean await(long timeout, TimeUnit unit) throws InterruptedException { if (pendingClose != null) pendingClose.run(); return count == 0; }
                }
                static class SimulatedDisplayLease { static final String SETTING = "overlay"; void close() throws IOException { events.add("lease-close"); } }
                static class WindowTransitionHealthDiagnostics {
                    boolean idle = true;
                    static WindowTransitionHealthDiagnostics awaitDisplayIdle(Object context, int display, long timeout) { return new WindowTransitionHealthDiagnostics(); }
                }
                static class MagicDeskApplication { static Object applicationContext() { return null; } }
                static class ExternalDisplayController { static boolean displayExists(int display) { return false; } }
                static class SystemClock { static long uptimeMillis() { return 0; } }
                static class BoundedStateAwaiter { enum Reason { DISPLAY_STATE } static void pause(Reason reason, long interval) {} }
                static final long STEP_TIMEOUT_MILLIS = 1000, POLL_MILLIS = 25, CLOSE_TIMEOUT_SECONDS = 60;
                static class DesktopSelfTestComponents { static final String FIXTURE_CLASS = "fixture", BROWSER_FIXTURE_CLASS = "browser"; }
                static String usefulMessage(Throwable error) { return error.getMessage(); }
                static Set<Integer> captureFixtureTaskIds() throws IOException { events.add("capture"); return Set.of(42); }
                static void removeFixtureTasks(Set<Integer> phone, Set<Integer> owned) throws IOException { check(owned.contains(42), "fixture identity lost"); events.add("remove"); if (failRemoval) throw new IOException("fixture removal failed"); }
                static void waitForTaskAbsent(String name) throws IOException {}
                static void verifyPhoneDestination(DesktopSelfTestResult result, boolean displayRemoved) { events.add("verify-home"); }
                static void waitForDesktopTaskAbsent() throws IOException {}
                static void waitForLocalDesktopCleanup() throws IOException {}
                static void waitForNoLiveDesktopTasks(int display) throws IOException {}
                static void waitForDesktopRepositoryEmpty(int display) throws IOException {}
                static void waitForTaskAbsentFromDesktopRepository(int display, int task) throws IOException {}
                public static void verify() { DesktopSelfTestResult result = new DesktopSelfTestResult();
                """ + scenario + "}\n" + RuntimeSourceFixture.methods("DesktopSelfTestCleanup",
                "run", "closeDesktopSessionAndWait", "requireShell"));
    }
}
