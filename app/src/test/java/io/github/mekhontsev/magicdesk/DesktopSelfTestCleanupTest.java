package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopSelfTestCleanupTest {
    @Test
    public void leavesPhoneDeskBeforeRemovingOnlyPhoneFreeformFixture() {
        assertTrue(DesktopSelfTestCleanup
                .requiresPhoneDesktopExitBeforeRemoval(
                        task(0, "freeform"), true));
        assertFalse(DesktopSelfTestCleanup
                .requiresPhoneDesktopExitBeforeRemoval(
                        task(0, "fullscreen"), true));
        assertFalse(DesktopSelfTestCleanup
                .requiresPhoneDesktopExitBeforeRemoval(
                        task(95, "freeform"), true));
        assertFalse(DesktopSelfTestCleanup
                .requiresPhoneDesktopExitBeforeRemoval(null, true));
        assertFalse(DesktopSelfTestCleanup
                .requiresPhoneDesktopExitBeforeRemoval(
                        task(0, "freeform"), false));
    }

    private static TaskStackParser.Entry task(
            final int displayId, final String windowingMode) {
        return new TaskStackParser.Entry(
                10,
                20,
                displayId,
                "io.github.mekhontsev.magicdesk",
                "io.github.mekhontsev.magicdesk/.DesktopSelfTestActivity",
                "io.github.mekhontsev.magicdesk/.DesktopSelfTestActivity",
                windowingMode,
                "standard",
                new TaskStackParser.Bounds(0, 0, 800, 600),
                true);
    }
}
