package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.assertEquals;

public final class RuntimeDisplayInputCoordinatorTest {
    @Test public void selectionWaitsForRoutingAndReportsSupersession() throws Exception {
        RuntimeSourceFixture.verify("""
                boolean mDestroyed;
                TaskRepository.ActionCallback mSelectionCompletion;
                final Target mTarget = new Target();
                final Session mInputSession = new Session();
                static class TaskRepository {
                    interface ActionCallback { void onComplete(ActionResult result); }
                    record ActionResult(boolean success, String message) {}
                }
                static class Target {
                    int id = -1;
                    boolean reject;
                    void select(int value) {
                        if (reject) throw new IllegalStateException("not prepared");
                        id = value;
                    }
                    int requestedDisplay() { return id; }
                }
                static class Session {
                    boolean pending;
                    int ready = -1;
                    String failure = "";
                    boolean transitioning() { return pending; }
                    int readyDisplay() { return ready; }
                    String error() { return failure; }
                }
                void updateInputBridges() { mInputSession.pending = true; }
                void updateShowImeOverride() {}
                public static void verify() {
                    Fixture f = new Fixture();
                    java.util.List<TaskRepository.ActionResult> a = new java.util.ArrayList<>();
                    java.util.List<TaskRepository.ActionResult> b = new java.util.ArrayList<>();
                    f.selectDisplay(7, a::add);
                    check(a.isEmpty(), "accepted input was reported ready");
                    f.mTarget.reject = true;
                    try { f.selectDisplay(8, b::add); } catch (IllegalStateException expected) {}
                    check(a.isEmpty() && b.isEmpty(), "invalid request consumed earlier completion");
                    f.mTarget.reject = false;
                    f.selectDisplay(8, b::add);
                    check(a.size() == 1 && !a.get(0).success(), "old selection was not superseded");
                    f.mInputSession.pending = false;
                    f.mInputSession.ready = 8;
                    f.finishSelectionIfSettled();
                    f.finishSelectionIfSettled();
                    check(b.size() == 1 && b.get(0).success(), "readiness completion was not exactly once");
                    f.selectDisplay(-1, a::add);
                    check(a.size() == 1, "release reported ready before routes were released");
                    f.mInputSession.pending = false;
                    f.mInputSession.ready = -1;
                    f.finishSelectionIfSettled();
                    check(a.size() == 2 && a.get(1).success(), "release never completed");
                    f.selectDisplay(9, b::add);
                    f.mInputSession.pending = false;
                    f.mInputSession.failure = "routing failed";
                    f.finishSelectionIfSettled();
                    check(b.size() == 2 && !b.get(1).success(), "routing failure became success");
                }
                """ + RuntimeSourceFixture.methods("RuntimeDisplayInputCoordinator",
                        "selectDisplay", "finishSelectionIfSettled", "completeSelection"));
    }

    @Test public void compositeKeyboardAndMouseShareOneRoute() throws Exception {
        final String dump = """
                Event Hub State:
                  1: Keyboard
                    Classes: KEYBOARD | ALPHAKEY | EXTERNAL
                    Path: /dev/input/event1
                    Location: shared-port
                    Identifier: vendor=0x1234, product=0x5678
                  2: Mouse
                    Classes: CURSOR | EXTERNAL
                    Path: /dev/input/event2
                    Location: shared-port
                    Identifier: vendor=0x1234, product=0x5678
                Input Reader State:
                """;
        assertEquals(Set.of("shared-port", "magicdesk-mouse"),
                DisplayInputRoutingSession.selectPorts(dump));
    }

    @Test public void virtualMouseCanBeAssociatedBeforeDeviceRegistration() throws Exception {
        assertEquals(Set.of("magicdesk-mouse"),
                DisplayInputRoutingSession.selectPorts("Event Hub State:\nInput Reader State:\n"));
    }

    @Test public void preparationAndClosingStateGateEveryReconcile() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Display { static final int INVALID_DISPLAY = -1; }
                static class ShellAccess { static boolean ready = true; static boolean isReady() { return ready; } }
                static class PhoneTouchpadController { static void release(int id) {} }
                boolean mPointerReleaseExpected;
                int mInputDisplayId = -1;
                final Target mTarget = new Target();
                static class Target {
                    int requested = 7, ready = -1;
                    int requestedDisplay() { return requested; }
                    int readyTarget() { return ready; }
                    boolean desktopShortcuts() { return true; }
                }
                final Session mInputSession = new Session();
                static class Session {
                    int requested = -1;
                    void reconcile(int id, boolean shortcuts) { requested = id; }
                }
                public static void verify() {
                    Fixture f = new Fixture();
                    f.updateInputBridges();
                    check(f.mInputSession.requested == -1, "input preceded preparation");
                    f.mTarget.ready = 7;
                    f.updateInputBridges();
                    check(f.mInputSession.requested == 7, "ready session did not route");
                    f.mTarget.requested = f.mTarget.ready = -1;
                    f.updateInputBridges();
                    check(f.mInputSession.requested == -1 && f.mPointerReleaseExpected,
                            "close allowed input reacquisition");
                    f.mTarget.requested = f.mTarget.ready = 7;
                    ShellAccess.ready = false;
                    f.updateInputBridges();
                    check(f.mInputSession.requested == -1, "shell loss retained routes");
                }
                """ + RuntimeSourceFixture.methods("RuntimeDisplayInputCoordinator", "updateInputBridges"));
    }
}
