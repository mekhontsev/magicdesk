package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.assertEquals;

public final class RuntimeDisplayInputCoordinatorTest {
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
