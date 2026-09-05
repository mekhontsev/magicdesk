package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.Arrays;

public final class DesktopRemovalFocusPolicyTest {
    @Test
    public void waitsUntilClosingTaskLeavesSnapshot() {
        assertNull(DesktopRemovalFocusPolicy.exposedTask(Arrays.asList(
                task(10, 3, false, false, "example/.Closing"),
                task(11, 3, true, true, "example/.Survivor")), 3, 10));
    }

    @Test
    public void homeStopsSearchBeforeStaleFocusedFullscreen() {
        final FrameworkTaskSnapshot home = task(20, 3, true, false,
                BuildConfig.APPLICATION_ID + "/.DesktopActivity");
        assertSame(home, DesktopRemovalFocusPolicy.exposedTask(Arrays.asList(
                home, task(11, 3, true, true, "example/.Fullscreen")), 3, 10));
    }

    @Test
    public void selectsTopFreeformRatherThanFocusedBackground() {
        final FrameworkTaskSnapshot front =
                task(12, 3, true, false, "example/.Freeform");
        assertSame(front, DesktopRemovalFocusPolicy.exposedTask(Arrays.asList(
                front, task(11, 3, true, true, "example/.Background")), 3, 10));
    }

    @Test
    public void preservesUnmanagedPhoneForegroundBoundary() {
        final FrameworkTaskSnapshot phone =
                task(12, 0, true, true, "com.termux/.app.TermuxActivity");
        assertSame(phone, DesktopRemovalFocusPolicy.exposedTask(Arrays.asList(
                phone, task(11, 0, true, false, "example/.DesktopApp")), 0, 10));
    }

    @Test
    public void skipsOtherDisplaysHiddenTasksAndInfrastructure() {
        final FrameworkTaskSnapshot survivor =
                task(12, 3, true, false, "example/.Survivor");
        assertSame(survivor, DesktopRemovalFocusPolicy.exposedTask(Arrays.asList(
                task(10, 0, true, true, "example/.Phone"),
                task(13, 3, false, true, "example/.Hidden"),
                task(14, 3, true, false,
                        BuildConfig.APPLICATION_ID + "/.DesktopChromeActivity"),
                task(15, 3, true, false,
                        BuildConfig.APPLICATION_ID + "/.TaskAreaBackstopActivity"),
                survivor), 3, 10));
    }

    private static FrameworkTaskSnapshot task(
            final int id, final int display, final boolean visible,
            final boolean focused, final String component) {
        final String packageName = component.substring(0, component.indexOf('/'));
        return new FrameworkTaskSnapshot(null, id, id, display, 1, 1, 1,
                null, null, component, component, packageName, packageName,
                10000, packageName, null, visible, focused, null);
    }
}
