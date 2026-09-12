package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class DesktopSelfTestPreparationTest {
    @Test
    public void targetAndHostPublicationMustNotOvertakeStartCompletion() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() throws Exception {
                    Fixture test = new Fixture();
                    DesktopAutomationEventJournal.event = () -> {
                        int step = DesktopAutomationEventJournal.waits;
                        if (step == 1) DesktopRuntimeBridge.session.target = new DesktopDisplayTarget();
                        if (step == 2) DesktopRuntimeBridge.session.host = true;
                        if (step == 3) DesktopOperations.transitioning = false;
                    };
                    check(test.awaitPreparedDesktop(DesktopDisplayOutput.Kind.WIRED), "start should finish");
                    check(DesktopAutomationEventJournal.waits == 3, "target or host alone admitted test");
                    check(test.awaitPreparedDesktop(DesktopDisplayOutput.Kind.WIRED), "already ready");
                    check(DesktopAutomationEventJournal.waits == 3, "ready workspace should not wait");
                }
                """);
    }

    @Test
    public void hostRegistrationCanFollowTransitionCompletion() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() throws Exception {
                    DesktopRuntimeBridge.session.target = new DesktopDisplayTarget();
                    DesktopOperations.transitioning = false;
                    DesktopAutomationEventJournal.event = () -> DesktopRuntimeBridge.session.host = true;
                    check(new Fixture().awaitPreparedDesktop(null), "host event completes preparation");
                    check(DesktopAutomationEventJournal.waits == 1, "missing host must wait");
                }
                """);
    }

    @Test
    public void absentWrongOrStillStartingWorkspaceDoesNotPass() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() throws Exception {
                    Fixture test = new Fixture();
                    check(!test.awaitPreparedDesktop(null), "missing workspace");
                    DesktopRuntimeBridge.session.target = new DesktopDisplayTarget();
                    DesktopRuntimeBridge.session.host = true;
                    check(!test.awaitPreparedDesktop(null), "start still in progress");
                    DesktopOperations.transitioning = false;
                    check(!test.awaitPreparedDesktop(DesktopDisplayOutput.Kind.WIRELESS), "wrong transport");
                    DesktopRuntimeBridge.session.target.id = 9;
                    check(!test.awaitPreparedDesktop(null), "wrong workspace");
                }
                """);
    }

    @Test
    public void cancellationAndRunReplacementCannotStartWindowChecks() throws Exception {
        RuntimeSourceFixture.verify(fixture() + """
                public static void verify() throws Exception {
                    Fixture test = new Fixture();
                    DesktopAutomationEventJournal.event = () -> DesktopSelfTestRunState.cancelled = true;
                    check(!test.awaitPreparedDesktop(null), "cancelled preparation");
                    DesktopSelfTestRunState.cancelled = false;
                    DesktopAutomationEventJournal.event = () -> DesktopSelfTestRunState.currentRun = 2;
                    check(!test.awaitPreparedDesktop(null), "superseded preparation");
                    DesktopSelfTestRunState.currentRun = 1;
                    DesktopAutomationEventJournal.interrupt = true;
                    try { test.awaitPreparedDesktop(null); throw new AssertionError("interruption lost"); }
                    catch (InterruptedException expected) { }
                }
                """);
    }

    private static String fixture() throws Exception {
        return """
                final long mRunId = 1;
                final Target mTarget = new Target();
                static class Target {
                    boolean matchesDisplay(int id, DesktopDisplayTarget target) {
                        return target != null && target.id == id;
                    }
                }
                static class DesktopDisplayOutput {
                    enum Kind { WIRED, WIRELESS }
                    Kind kind = Kind.WIRED;
                }
                static class DesktopDisplayTarget {
                    int id = 52;
                    DesktopDisplayOutput output = new DesktopDisplayOutput();
                }
                static class DesktopSessionSnapshot {
                    DesktopDisplayTarget target;
                    boolean host;
                    DesktopDisplayTarget target() { return target; }
                    boolean hasHost() { return host; }
                }
                static class DesktopRuntimeBridge {
                    static DesktopSessionSnapshot session = new DesktopSessionSnapshot();
                    static DesktopSessionSnapshot getSessionSnapshot(int id) {
                        check(id == 52, "read exact prepared display"); return session;
                    }
                }
                static class DesktopOperations {
                    static boolean transitioning = true;
                    static boolean isSessionTransitionInProgress() { return transitioning; }
                }
                static class DesktopSelfTestRunState {
                    static long currentRun = 1;
                    static boolean cancelled;
                    boolean cancellationRequested;
                    static boolean isStarting(long run) { return run == currentRun; }
                    static int preparedDisplayId() { return 52; }
                    static DesktopSelfTestRunState snapshot() {
                        DesktopSelfTestRunState state = new DesktopSelfTestRunState();
                        state.cancellationRequested = cancelled; return state;
                    }
                }
                static class SystemClock {
                    static long now;
                    static long uptimeMillis() { return now; }
                }
                static class ExternalDisplayController { static final long START_TIMEOUT_MS = 50; }
                static class DesktopAutomationEventJournal {
                    static int waits;
                    static boolean interrupt;
                    static Runnable event = () -> {};
                    static long latestId() { return waits; }
                    static long awaitChange(long id, long remaining) throws InterruptedException {
                        check(id == waits, "do not lose an event between observation and wait");
                        if (interrupt) throw new InterruptedException();
                        waits++; SystemClock.now += 25; event.run(); return waits;
                    }
                }
                """ + RuntimeSourceFixture.methods("DesktopSelfTestLauncher", "awaitPreparedDesktop");
    }
}
