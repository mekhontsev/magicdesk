package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopSelfTestPhoneUiObserverTest {
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
