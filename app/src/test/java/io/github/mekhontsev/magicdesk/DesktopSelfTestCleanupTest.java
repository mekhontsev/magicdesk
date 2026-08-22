package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopSelfTestCleanupTest {
    private static final String PRIMARY_HOME =
            "com.zte.mifavor.launcher/"
                    + "com.android.launcher3.uioverrides.QuickstepLauncher";
    private static final String SECONDARY_HOME =
            "com.zte.mifavor.launcher/"
                    + "com.android.launcher3.secondarydisplay."
                    + "SecondaryDisplayLauncher";
    private static final PhoneHomeComponents HOME =
            PhoneHomeComponents.forTests(PRIMARY_HOME, SECONDARY_HOME);

    @Test
    public void ignoresSecondaryBaseAfterPrimaryHomeTakesOverTask() {
        assertNull(DesktopSelfTestCleanup.findDedicatedSecondaryHomeTask(
                stack(SECONDARY_HOME, PRIMARY_HOME), HOME));
    }

    @Test
    public void detectsDedicatedSecondaryHomeRemainingOnPhone() {
        assertEquals(20988, DesktopSelfTestCleanup
                .findDedicatedSecondaryHomeTask(
                        stack(SECONDARY_HOME, SECONDARY_HOME), HOME).taskId);
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

    private static String stack(
            final String component,
            final String topActivity) {
        return "RootTask id=1 bounds=[0,0][1216,2688] displayId=0\n"
                + "  configuration={ mWindowingMode=fullscreen"
                + " mActivityType=home }\n"
                + "  taskId=20988: " + component
                + " bounds=[0,0][1216,2688] userId=0 visible=false"
                + " topActivity=ComponentInfo{" + topActivity + "}\n";
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
