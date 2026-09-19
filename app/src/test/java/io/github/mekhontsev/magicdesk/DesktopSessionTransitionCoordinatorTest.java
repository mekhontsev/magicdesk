package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopSessionTransitionCoordinatorTest {
    @Test public void startReportsGateRejectionAndTerminalFailure() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                final DesktopTransitionGate mGate = new DesktopTransitionGate();
                final Operations mOperations = new Operations();
                static final String TAG = "test";
                static class Operations {
                    final Deque<Runnable> queue = new ArrayDeque<>();
                    void execute(Runnable r) { queue.add(r); }
                }
                static class TaskRepository {
                    interface ActionCallback { void onComplete(ActionResult result); }
                    record ActionResult(boolean success, String message) {}
                }
                static class DesktopSessionController {
                    record ShowResult(boolean ready, String error) {}
                }
                static class Log { static void i(String tag, String message) {} }
                static class ShellAccess { static String usefulMessage(Throwable error) { return error.getMessage(); } }
                static class CompatibilityDiagnostics { static void record(Object... args) {} }
                boolean synchronizeProjectionState() { return true; }
                void finishOperation(DesktopTransitionGate.Operation op) { mGate.finish(op); }
                public static void verify() {
                    Fixture f = new Fixture();
                    List<TaskRepository.ActionResult> results = new ArrayList<>();
                    f.mGate.begin(DesktopTransitionGate.Operation.CLOSE);
                    check(!f.enqueueDesktopStart(() -> { throw new AssertionError("rejected start ran"); }, results::add),
                            "rejected start reported accepted");
                    check(results.size() == 1 && !results.get(0).success(), "gate rejection not delivered");
                    f.mGate.finish(DesktopTransitionGate.Operation.CLOSE);
                    check(f.enqueueDesktopStart(() -> new DesktopSessionController.ShowResult(false, "host failed"),
                            results::add), "start not accepted");
                    check(results.size() == 1, "accepted request reported completed");
                    f.mOperations.queue.remove().run();
                    check(results.size() == 2 && results.get(1).message().equals("host failed"), "failure was swallowed");
                    f.enqueueDesktopStart(() -> { throw new IllegalStateException("gone"); }, results::add);
                    f.mOperations.queue.remove().run();
                    check(results.size() == 3 && !results.get(2).success(), "exception not delivered");
                    check(f.mGate.begin(DesktopTransitionGate.Operation.CLOSE), "failure kept transition gate");
                }
                """ + RuntimeSourceFixture.methods("DesktopSessionTransitionCoordinator", "enqueueDesktopStart"),
                "DesktopTransitionGate");
    }

    @Test
    public void visiblePhonePanelIsReused() {
        assertFalse(DesktopSessionTransitionCoordinator.shouldOpenPhonePanel(
                DesktopCloseMode.CONTROL_PANEL, true));
    }

    @Test
    public void missingPhonePanelIsRestoredWhenRequested() {
        assertTrue(DesktopSessionTransitionCoordinator.shouldOpenPhonePanel(
                DesktopCloseMode.CONTROL_PANEL, false));
    }

    @Test
    public void phonePanelIsNotOpenedDuringFullExit() {
        assertFalse(DesktopSessionTransitionCoordinator.shouldOpenPhonePanel(
                DesktopCloseMode.EXIT, false));
    }

    @Test
    public void closeFromHomeOrOverviewParksTasksWithoutOpeningControls() {
        org.junit.Assert.assertEquals(DesktopSessionEndPlan.Tasks.RETURN_TO_DEFAULT_AND_REMEMBER, plan(DesktopCloseMode.HOME).tasks);
        assertFalse(DesktopSessionTransitionCoordinator.shouldOpenPhonePanel(
                DesktopCloseMode.HOME, false));
        assertFalse(DesktopSessionTransitionCoordinator.shouldOpenPhonePanel(
                DesktopCloseMode.HOME, true));
    }

    @Test
    public void closeToControlsAlsoParksTasksWhenPanelIsAlreadyVisible() {
        org.junit.Assert.assertEquals(DesktopSessionEndPlan.Tasks.RETURN_TO_DEFAULT_AND_REMEMBER, plan(DesktopCloseMode.CONTROL_PANEL).tasks);
        assertFalse(DesktopSessionTransitionCoordinator.shouldOpenPhonePanel(
                DesktopCloseMode.CONTROL_PANEL, true));
    }

    @Test
    public void exitReturnsTasksWithoutRetainingThem() {
        org.junit.Assert.assertEquals(DesktopSessionEndPlan.Tasks.RETURN_TO_DEFAULT, plan(DesktopCloseMode.EXIT).tasks);
    }

    private static DesktopSessionEndPlan plan(final DesktopCloseMode mode) {
        return DesktopSessionEndPlan.create(DesktopWorkspaceSnapshot.empty(),
                DesktopDisplayTarget.wired(7), mode, true);
    }
}
