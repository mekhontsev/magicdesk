package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopSelfTestControllerTest {
    @Test
    public void findsFixtureOnlyOnRequestedDisplay() {
        final String stack =
                "RootTask id=11 displayId=0\n"
                        + " configuration={mWindowingMode=fullscreen}\n"
                        + " taskId=21: io.github.mekhontsev.magicdesk/"
                        + ".DesktopSelfTestActivity "
                        + "topActivity=ComponentInfo{"
                        + "io.github.mekhontsev.magicdesk/"
                        + ".DesktopSelfTestActivity} visible=true\n"
                        + "RootTask id=12 displayId=8\n"
                        + " configuration={mWindowingMode=freeform}\n"
                        + " taskId=22: io.github.mekhontsev.magicdesk/"
                        + ".DesktopSelfTestActivity "
                        + "topActivity=ComponentInfo{"
                        + "io.github.mekhontsev.magicdesk/"
                        + ".DesktopSelfTestActivity} visible=true\n";

        assertEquals(22, DesktopSelfTestController.findTask(
                stack, 8,
                "io.github.mekhontsev.magicdesk.DesktopSelfTestActivity").taskId);
        assertNull(DesktopSelfTestController.findTask(
                stack, 3,
                "io.github.mekhontsev.magicdesk.DesktopSelfTestActivity"));
    }

    @Test
    public void appliesPredicateAcrossMatchingFixtureTasks() {
        final String fixture =
                "io.github.mekhontsev.magicdesk.DesktopSelfTestActivity";
        final String stack =
                "RootTask id=12 displayId=8\n"
                        + " configuration={mWindowingMode=freeform}\n"
                        + " taskId=22: io.github.mekhontsev.magicdesk/"
                        + ".DesktopSelfTestActivity topActivity=ComponentInfo{"
                        + "io.github.mekhontsev.magicdesk/"
                        + ".DesktopSelfTestActivity} visible=true\n"
                        + "RootTask id=13 displayId=8\n"
                        + " configuration={mWindowingMode=freeform}\n"
                        + " taskId=23: io.github.mekhontsev.magicdesk/"
                        + ".DesktopSelfTestActivity topActivity=ComponentInfo{"
                        + "io.github.mekhontsev.magicdesk/"
                        + ".DesktopSelfTestActivity} visible=true\n";

        assertEquals(23, DesktopSelfTestController.findTask(
                stack, 8, fixture, task -> task.taskId == 23).taskId);
    }

    @Test
    public void findsFrontVisibleTaskIncludingDesktopHost() {
        final String stack =
                "RootTask id=10 displayId=8\n"
                        + " configuration={mWindowingMode=fullscreen}\n"
                        + " taskId=20: io.github.mekhontsev.magicdesk/"
                        + ".DesktopActivity topActivity=ComponentInfo{"
                        + "io.github.mekhontsev.magicdesk/.DesktopActivity} "
                        + "visible=true\n"
                        + "RootTask id=12 displayId=8\n"
                        + " configuration={mWindowingMode=freeform}\n"
                        + " taskId=22: io.github.mekhontsev.magicdesk/"
                        + ".DesktopSelfTestActivity topActivity=ComponentInfo{"
                        + "io.github.mekhontsev.magicdesk/"
                        + ".DesktopSelfTestActivity} visible=true\n"
                        + "RootTask id=13 displayId=8\n"
                        + " configuration={mWindowingMode=freeform}\n"
                        + " taskId=23: io.github.mekhontsev.magicdesk/"
                        + ".DesktopSelfTestActivity topActivity=ComponentInfo{"
                        + "io.github.mekhontsev.magicdesk/"
                        + ".DesktopSelfTestActivity} visible=true\n";

        assertEquals(20, DesktopSelfTestController.findFrontTask(
                stack, 8).taskId);
    }

    @Test
    public void leavesPhoneDeskBeforeRemovingOnlyPhoneFreeformFixture() {
        assertTrue(DesktopSelfTestController
                .requiresPhoneDesktopExitBeforeRemoval(task(0, "freeform")));
        assertFalse(DesktopSelfTestController
                .requiresPhoneDesktopExitBeforeRemoval(task(0, "fullscreen")));
        assertFalse(DesktopSelfTestController
                .requiresPhoneDesktopExitBeforeRemoval(task(95, "freeform")));
        assertFalse(DesktopSelfTestController
                .requiresPhoneDesktopExitBeforeRemoval(null));
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
