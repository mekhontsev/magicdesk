package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopTaskbarVisibilityPolicyTest {
    @Test
    public void localFreeformTaskKeepsTaskbarVisible() {
        assertTrue(DesktopTaskbarVisibilityPolicy.isVisible(
                true, true, true, false, false, false));
    }

    @Test
    public void localFullscreenTaskAndLauncherHideTaskbar() {
        assertFalse(DesktopTaskbarVisibilityPolicy.isVisible(
                true, true, false, true, false, true));
    }

    @Test
    public void visibleFreeformAboveActiveFullscreenKeepsTaskbarVisible() {
        assertTrue(DesktopTaskbarVisibilityPolicy.isVisible(
                false, true, true, true, false, false));
    }

    @Test
    public void incompleteLocalSnapshotPreservesLifecycleState() {
        assertFalse(DesktopTaskbarVisibilityPolicy.isVisible(
                true, false, false, false, false, false));
        assertTrue(DesktopTaskbarVisibilityPolicy.isVisible(
                true, false, false, false, false, true));
    }

    @Test
    public void desktopHostAndIdleExternalDesktopShowTaskbar() {
        assertTrue(DesktopTaskbarVisibilityPolicy.isVisible(
                true, true, false, false, true, false));
        assertTrue(DesktopTaskbarVisibilityPolicy.isVisible(
                false, false, false, false, false, false));
    }

    @Test
    public void visibleFullscreenHidesTaskbarWithoutActiveFlag() {
        assertFalse(DesktopTaskbarVisibilityPolicy.isVisible(
                false, false, false, true, false, true));
    }
}
