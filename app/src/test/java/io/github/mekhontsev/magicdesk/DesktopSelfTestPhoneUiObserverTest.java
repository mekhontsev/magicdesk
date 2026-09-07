package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopSelfTestPhoneUiObserverTest {
    @Test
    public void waitsForRestoredVisibilityWithoutOpeningOrRepairingTheTouchpad()
            throws Exception {
        RuntimeSourceFixture.verify("""
                static boolean requested, visible, reveal, interrupt;
                static long now;
                static int waits;
                static class SystemClock { static long uptimeMillis() { return now; } }
                static class PhoneTouchpadController {
                    static boolean shouldRemainVisible(int display) { return requested; }
                }
                static class MagicDeskTouchpadActivity {
                    static boolean isVisible(int display) { return visible; }
                }
                static class DesktopAutomationEventJournal {
                    static long latestId() { return 10; }
                    static long awaitChange(long event, long remaining) throws InterruptedException {
                        waits++;
                        check(remaining > 0, "invalid event timeout");
                        if (interrupt) throw new InterruptedException();
                        if (reveal) { visible = true; now++; }
                        else now += remaining;
                        return event + 1;
                    }
                }
                public static void verify() throws Exception {
                    awaitTouchpadRestored(7, 500);
                    check(waits == 0, "waited without a touchpad request");
                    requested = true; visible = true;
                    awaitTouchpadRestored(7, 500);
                    check(waits == 0, "waited after visibility was committed");
                    visible = false; reveal = true;
                    awaitTouchpadRestored(7, 500);
                    check(waits == 1 && visible, "did not observe lifecycle event");
                    visible = false; reveal = false;
                    try {
                        awaitTouchpadRestored(7, 500);
                        throw new AssertionError("missing touchpad passed");
                    } catch (IOException expected) { }
                    interrupt = true;
                    try {
                        awaitTouchpadRestored(7, 500);
                        throw new AssertionError("interrupted wait passed");
                    } catch (IOException expected) {
                        check(Thread.interrupted(), "lost interruption");
                    }
                }
                """ + RuntimeSourceFixture.methods("DesktopSelfTestPhoneUiObserver",
                        "awaitTouchpadRestored"));
    }

    @Test
    public void rejectsOnlyUnexpectedFreeformTasksAfterTheBaseline() {
        final DesktopSelfTestPhoneUiObserver.PhoneTaskModeGuard guard =
                new DesktopSelfTestPhoneUiObserver.PhoneTaskModeGuard();

        assertNull(guard.observe(1, "freeform"));
        guard.completeBaseline();
        assertNull(guard.observe(2, "fullscreen"));
        assertFalse(guard.violated());

        guard.observe(3, "freeform");
        assertTrue(guard.violated());
    }

    @Test
    public void rejectsAWindowingModeChangeAfterTheBaseline() {
        final DesktopSelfTestPhoneUiObserver.PhoneTaskModeGuard guard =
                new DesktopSelfTestPhoneUiObserver.PhoneTaskModeGuard();

        guard.observe(1, "fullscreen");
        guard.completeBaseline();
        guard.observe(1, "freeform");

        assertTrue(guard.violated());
    }

    @Test
    public void requiresTheRequestedTouchpadToBeRestored() {
        assertTrue(observation(
                true, true, true, false, false).touchpadStable());
        assertFalse(observation(
                false, true, true, false, false).touchpadStable());
        assertFalse(observation(
                true, false, true, false, false).touchpadStable());
        assertTrue(observation(
                true, true, true, true, false).touchpadStable());
        assertFalse(observation(
                true, true, false, false, true).touchpadStable());
        assertTrue(observation(
                true, true, true, false, true).touchpadStable());
    }

    private static DesktopSelfTestPhoneUiObserver.Observation observation(
            final boolean requested,
            final boolean seen,
            final boolean visible,
            final boolean stopped,
            final boolean missing) {
        return new DesktopSelfTestPhoneUiObserver.Observation(
                true,
                true,
                requested,
                seen,
                visible,
                stopped,
                missing,
                false,
                false,
                "test");
    }
}
