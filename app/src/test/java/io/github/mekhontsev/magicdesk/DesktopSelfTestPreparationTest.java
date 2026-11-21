package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;

public final class DesktopSelfTestPreparationTest {
    @Test public void homeRejectionReturnsItsCauseWithoutWaitingForAHost() throws Exception {
        verify("""
                DesktopOperations.success = false;
                DesktopOperations.message = "HOME role assignment denied";
                try { showReady(new DesktopDisplayTarget()); throw new AssertionError("failure lost"); }
                catch (IOException expected) { check(expected.getMessage().contains("HOME role assignment denied"), "cause lost"); }
                check(mStartAttempted && DesktopOperations.completed, "rollback completion not awaited");
                """);
    }

    @Test public void targetAndHostPublicationCannotOvertakeStartCompletion() throws Exception {
        verify("""
                showReady(new DesktopDisplayTarget());
                check(DesktopOperations.completed, "returned before production completion");
                check(CountDownLatch.waits == 1, "must await the exact start callback");
                """);
    }

    @Test public void absentCompletionAndInterruptedWaitCannotPass() throws Exception {
        verify("""
                DesktopOperations.complete = false;
                try { showReady(new DesktopDisplayTarget()); throw new AssertionError("timeout lost"); }
                catch (IOException expected) { check(expected.getMessage().contains("timed out"), "wrong timeout"); }
                CountDownLatch.interrupt = true;
                try { showReady(new DesktopDisplayTarget()); throw new AssertionError("interruption lost"); }
                catch (IOException expected) { check(Thread.interrupted(), "interrupt flag lost"); }
                """);
    }

    @Test public void closedLeaseCannotStartDesktop() throws Exception {
        verify("""
                mClosed = true;
                try { showReady(new DesktopDisplayTarget()); throw new AssertionError("closed lease used"); }
                catch (IllegalStateException expected) { }
                check(!mStartAttempted && !DesktopOperations.requested, "closed lease submitted start");
                """);
    }

    @Test public void allTargetsProbeBeforeStartingDesktop() throws Exception {
        final String launcher = source("DesktopSelfTestLauncher");
        assertFalse(launcher.contains("DesktopOperations.show"));
        assertFalse(launcher.contains("DesktopHomeRoleLease"));
        final String controller = source("DesktopSelfTestController");
        assertTrue(controller.indexOf("DesktopSelfTestFreeformProbe.run(")
                < controller.indexOf("DesktopSelfTestWindowSuite.run("));
        final String suite = source("DesktopSelfTestWindowSuite");
        final String preparation = suite.substring(suite.indexOf("\"DESKTOP-001\""),
                suite.indexOf("\"DESKTOP-002\""));
        assertTrue(preparation.contains("workspaceLease.showReady(displayTarget)"));
        assertFalse(preparation.contains("target == DesktopSelfTestTarget.SIMULATED"));
    }

    @Test public void independentLaunchPreservesComponentAndExtrasAcrossShellTransport() throws Exception {
        final String probe = source("DesktopSelfTestFreeformProbe");
        assertTrue(probe.contains("\"app \" + displayId"));
        assertFalse(probe.contains("app-direct"));
        assertTrue(probe.contains("intent.toUri(Intent.URI_INTENT_SCHEME)"));
        assertFalse(probe.contains("intent.toUri(0)"));
        assertTrue(source("TaskDisplayAreaLaunchCommand")
                .contains("Intent.parseUri(intentUri, Intent.URI_INTENT_SCHEME)"));
    }

    private static String source(String name) throws Exception {
        return Files.readString(Path.of("src/main/java/io/github/mekhontsev/magicdesk/" + name + ".java"));
    }

    private static void verify(String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static boolean mClosed, mStartAttempted;
                static class DesktopDisplayTarget { int workspaceDisplayId = 7; }
                static class DesktopSelfTestRunState {
                    long runId = 1;
                    static int expectedClose = -1;
                    static DesktopSelfTestRunState snapshot() { return new DesktopSelfTestRunState(); }
                    static void expectSessionClose(long run, int display) { expectedClose = display; }
                    static void resumeSessionCloseObservation(long run, int display) { expectedClose = -1; }
                }
                enum DesktopSessionPolicy { ISOLATED_SELF_TEST }
                static class ExternalDisplayController { static final long START_TIMEOUT_MS = 50; }
                static class TaskRepository {
                    record ActionResult(boolean success, String message) { }
                    interface ActionCallback { void onComplete(ActionResult result); }
                }
                static class DesktopOperations {
                    static boolean requested, completed, complete = true, success = true;
                    static String message = "ready";
                    static Runnable pending;
                    static boolean showDesktop(DesktopDisplayTarget target, DesktopSessionPolicy policy,
                            TaskRepository.ActionCallback callback) {
                        check(policy == DesktopSessionPolicy.ISOLATED_SELF_TEST, "user workspace touched");
                        requested = true;
                        pending = () -> {
                            check(DesktopSelfTestRunState.expectedClose == 7, "startup rollback could cancel the run");
                            completed = true; callback.onComplete(new TaskRepository.ActionResult(success, message));
                        };
                        return true;
                    }
                }
                static class CountDownLatch {
                    static int waits;
                    static boolean interrupt;
                    boolean ready;
                    CountDownLatch(int count) { }
                    void countDown() { ready = true; }
                    boolean await(long timeout, TimeUnit unit) throws InterruptedException {
                        waits++;
                        if (interrupt) throw new InterruptedException();
                        if (DesktopOperations.complete) DesktopOperations.pending.run();
                        return ready;
                    }
                }
                public static void verify() throws Exception { new Fixture().exercise(); }
                void exercise() throws Exception {
                """ + scenario + "check(DesktopSelfTestRunState.expectedClose == -1, \"session loss observation not restored\");}\n"
                + RuntimeSourceFixture.methods("WorkspaceIsolationLease", "showReady", "requireOpen"));
    }
}
