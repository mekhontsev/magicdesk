package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ShellFullscreenTaskAreaTest {
    private static final int FULLSCREEN = 1;
    private static final int FREEFORM = 5;

    @Test
    public void joinsOnlyAnExistingDefaultFullscreenHierarchy() {
        assertTrue(ShellFullscreenTaskArea.shouldJoinPreparedArea(
                DesktopTaskAreaPolicy.DEFAULT, true, 2));

        assertFalse(ShellFullscreenTaskArea.shouldJoinPreparedArea(
                DesktopTaskAreaPolicy.DEFAULT, false, 2));
        assertFalse(ShellFullscreenTaskArea.shouldJoinPreparedArea(
                DesktopTaskAreaPolicy.DEFAULT, true, 0));
        assertFalse(ShellFullscreenTaskArea.shouldJoinPreparedArea(
                DesktopTaskAreaPolicy.SESSION, true, 2));
    }

    @Test
    public void applicationFullscreenRetainsPhoneSessionParent() {
        assertTrue(ShellFullscreenTaskArea.shouldUseSessionParent(
                DesktopTaskAreaPolicy.SESSION));
        assertFalse(ShellFullscreenTaskArea.shouldUseSessionParent(
                DesktopTaskAreaPolicy.DEFAULT));
    }

    @Test
    public void releasesOnlyBackgroundDefaultParentAppFullscreen() {
        assertTrue(ShellFullscreenTaskArea.shouldReleaseBackgroundAppFullscreen(
                DesktopTaskAreaPolicy.DEFAULT,
                false,
                FREEFORM,
                true,
                false));

        assertFalse(ShellFullscreenTaskArea.shouldReleaseBackgroundAppFullscreen(
                DesktopTaskAreaPolicy.SESSION,
                false,
                FREEFORM,
                true,
                false));
        assertFalse(ShellFullscreenTaskArea.shouldReleaseBackgroundAppFullscreen(
                DesktopTaskAreaPolicy.DEFAULT,
                true,
                FREEFORM,
                true,
                false));
        assertFalse(ShellFullscreenTaskArea.shouldReleaseBackgroundAppFullscreen(
                DesktopTaskAreaPolicy.DEFAULT,
                false,
                FULLSCREEN,
                true,
                false));
        assertFalse(ShellFullscreenTaskArea.shouldReleaseBackgroundAppFullscreen(
                DesktopTaskAreaPolicy.DEFAULT,
                false,
                FREEFORM,
                false,
                false));
        assertFalse(ShellFullscreenTaskArea.shouldReleaseBackgroundAppFullscreen(
                DesktopTaskAreaPolicy.DEFAULT,
                false,
                FREEFORM,
                true,
                true));
    }
}
