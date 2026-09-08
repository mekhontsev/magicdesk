package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.util.Set;
import static org.junit.Assert.assertEquals;

public final class RuntimeDesktopInputCoordinatorTest {
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
                DesktopInputRoutingSession.selectPorts(dump));
    }

    @Test public void virtualMouseCanBeAssociatedBeforeDeviceRegistration() throws Exception {
        assertEquals(Set.of("magicdesk-mouse"),
                DesktopInputRoutingSession.selectPorts("Event Hub State:\nInput Reader State:\n"));
    }

    @Test public void preparationAndClosingStateGateEveryReconcile() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Display { static final int INVALID_DISPLAY = -1; }
                static class ShellAccess { static boolean ready = true; static boolean isReady() { return ready; } }
                boolean mDesktopPrepared, mPointerReleaseExpected;
                int mDesktopDisplayId = 7, mClosingInputDisplayId = -1;
                final Session mInputSession = new Session();
                static class Session {
                    int requested = -1;
                    boolean isPointerReady(int id) { return requested == id; }
                    void reconcile(int id) { requested = id; }
                }
                public static void verify() {
                    Fixture f = new Fixture();
                    f.updateInputBridges();
                    check(f.mInputSession.requested == -1, "input preceded preparation");
                    f.mDesktopPrepared = true;
                    f.updateInputBridges();
                    check(f.mInputSession.requested == 7, "ready session did not route");
                    f.mClosingInputDisplayId = 7;
                    f.updateInputBridges();
                    check(f.mInputSession.requested == -1 && f.mPointerReleaseExpected,
                            "close allowed input reacquisition");
                    f.mClosingInputDisplayId = -1;
                    ShellAccess.ready = false;
                    f.updateInputBridges();
                    check(f.mInputSession.requested == -1, "shell loss retained routes");
                }
                """ + RuntimeSourceFixture.methods("RuntimeDesktopInputCoordinator", "updateInputBridges"));
    }
}
