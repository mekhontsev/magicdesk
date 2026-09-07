package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopSelfTestCleanupTest {
    @Test
    public void homeCheckRejectsSecondaryLauncherFromSamePackage() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Display { static final int DEFAULT_DISPLAY = 0; }
                record ComponentName(String name) {
                    static ComponentName unflattenFromString(String name) { return new ComponentName(name); }
                }
                static class FrameworkTaskSnapshot {
                    int displayId;
                    boolean home = true, visible = true, focused = true;
                    ComponentName topComponent = new ComponentName("launcher/Primary");
                    boolean isHome() { return home; }
                }
                public static void verify() {
                    var task = new FrameworkTaskSnapshot();
                    var expected = new ComponentName("launcher/Primary");
                    check(isExpectedPhoneDestination(task, expected, true), "primary HOME rejected");
                    task.topComponent = new ComponentName("launcher/Secondary");
                    check(!isExpectedPhoneDestination(task, expected, true), "secondary HOME accepted");
                    task.topComponent = new ComponentName("launcher/Primary");
                    task.displayId = 7;
                    check(!isExpectedPhoneDestination(task, expected, true), "external HOME accepted");
                    task.displayId = 0; task.focused = false;
                    check(!isExpectedPhoneDestination(task, expected, true), "background HOME accepted");
                    task.focused = true; task.visible = false;
                    check(!isExpectedPhoneDestination(task, expected, true), "invisible HOME accepted");
                    task.visible = true; task.home = false;
                    check(!isExpectedPhoneDestination(task, expected, true), "ordinary launcher window accepted");
                    expected = new ComponentName("magicdesk/ControlActivity");
                    check(!isExpectedPhoneDestination(task, expected, false), "launcher accepted instead of control panel");
                    task.topComponent = new ComponentName("magicdesk/ControlActivity");
                    check(isExpectedPhoneDestination(task, expected, false), "control panel rejected after display loss");
                }
                """ + RuntimeSourceFixture.methods("DesktopSelfTestCleanup", "isExpectedPhoneDestination"));
    }

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
