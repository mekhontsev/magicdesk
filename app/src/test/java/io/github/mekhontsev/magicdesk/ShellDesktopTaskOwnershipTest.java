package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ShellDesktopTaskOwnershipTest {
    @Test
    public void desktopHostIdentityFollowsSessionAndTaskLifecycle() {
        final ShellDesktopTaskOwnership ownership =
                new ShellDesktopTaskOwnership();

        ownership.configure(4);
        ownership.markDesktopHost(41);
        assertTrue(ownership.isDesktopHostTask(41));
        assertFalse(ownership.isDesktopHostTask(42));

        ownership.forget(41);
        assertFalse(ownership.isDesktopHostTask(41));

        ownership.markDesktopHost(43);
        ownership.configure(5);
        assertFalse(ownership.isDesktopHostTask(43));
    }

    private static final int WINDOWING_MODE_FULLSCREEN = 1;
    private static final int WINDOWING_MODE_FREEFORM = 5;

    @Test
    public void everyFreeformTaskIsDesktopOwned() {
        assertTrue(ShellDesktopTaskOwnership.isDesktopOwnedMode(
                WINDOWING_MODE_FREEFORM, false, false));
        assertTrue(ShellDesktopTaskOwnership.isDesktopOwnedMode(
                WINDOWING_MODE_FULLSCREEN, true, false));
        assertFalse(ShellDesktopTaskOwnership.isDesktopOwnedMode(
                WINDOWING_MODE_FULLSCREEN, false, false));
        assertFalse(ShellDesktopTaskOwnership.isDesktopOwnedMode(
                WINDOWING_MODE_FREEFORM, false, true));
    }

    @Test
    public void restoresEveryObservedUnownedPhoneFreeformState() {
        assertTrue(shouldRestore(
                true, true, false, true,
                WINDOWING_MODE_FREEFORM));
        assertFalse(shouldRestore(
                true, true, true, true,
                WINDOWING_MODE_FREEFORM));
        assertFalse(shouldRestore(
                false, true, false, true,
                WINDOWING_MODE_FREEFORM));
        assertFalse(shouldRestore(
                true, false, false, true,
                WINDOWING_MODE_FREEFORM));
        assertFalse(shouldRestore(
                true, true, false, false,
                WINDOWING_MODE_FREEFORM));
        assertFalse(shouldRestore(
                true, true, false, true,
                WINDOWING_MODE_FULLSCREEN));
    }

    private static boolean shouldRestore(
            final boolean localDesktop,
            final boolean phoneDisplay,
            final boolean desktopOwned,
            final boolean knownPhoneFullscreen,
            final int currentMode) {
        return ShellDesktopTaskOwnership.shouldRestoreKnownPhoneFreeform(
                localDesktop,
                phoneDisplay,
                desktopOwned,
                knownPhoneFullscreen,
                currentMode);
    }
}
