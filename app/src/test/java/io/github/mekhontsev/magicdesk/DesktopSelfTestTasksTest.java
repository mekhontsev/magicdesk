package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopSelfTestTasksTest {
    @Test
    public void resolvesAbbreviatedActivityAgainstRequestedClassPackage() {
        assertTrue(DesktopSelfTestTasks.hasClass(
                "com.example/.Fixture", "com.example.Fixture"));
        assertFalse(DesktopSelfTestTasks.hasClass(
                "com.other/.Fixture", "com.example.Fixture"));
    }

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

        assertEquals(22, DesktopSelfTestTasks.findTask(
                stack, 8,
                "io.github.mekhontsev.magicdesk.DesktopSelfTestActivity").taskId);
        assertNull(DesktopSelfTestTasks.findTask(
                stack, 3,
                "io.github.mekhontsev.magicdesk.DesktopSelfTestActivity"));
    }

    @Test
    public void recognizesPhoneDesktopHomeAsDesktopTask() {
        final String stack =
                "RootTask id=1 displayId=0\n"
                        + " configuration={mWindowingMode=fullscreen "
                        + "mActivityType=home}\n"
                        + " taskId=20: io.github.mekhontsev.magicdesk/"
                        + ".PhoneDesktopHomeActivity topActivity=ComponentInfo{"
                        + "io.github.mekhontsev.magicdesk/"
                        + ".PhoneDesktopHomeActivity} "
                        + "visible=true\n";

        assertEquals(20, DesktopSelfTestTasks.findDesktopTaskOnAnyDisplay(
                stack).taskId);
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

        assertEquals(23, DesktopSelfTestTasks.findTask(
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

        assertEquals(20, DesktopSelfTestTasks.findFrontTask(
                stack, 8).taskId);
    }

    @Test
    public void ignoresStructuralFullscreenSlotAnchor() {
        final String stack =
                "RootTask id=12 displayId=8\n"
                        + " configuration={mWindowingMode=fullscreen}\n"
                        + " taskId=22: io.github.mekhontsev.magicdesk/"
                        + ".TaskAreaBackstopActivity "
                        + "topActivity=ComponentInfo{"
                        + "io.github.mekhontsev.magicdesk/"
                        + ".TaskAreaBackstopActivity} visible=true\n"
                        + "RootTask id=10 displayId=8\n"
                        + " configuration={mWindowingMode=fullscreen}\n"
                        + " taskId=20: io.github.mekhontsev.magicdesk/"
                        + ".DesktopActivity topActivity=ComponentInfo{"
                        + "io.github.mekhontsev.magicdesk/.DesktopActivity} "
                        + "visible=true\n";

        assertEquals(20, DesktopSelfTestTasks.findFrontTask(
                stack, 8).taskId);
    }

    @Test
    public void ignoresDesktopChromeWhenFindingFrontApplication() {
        final String stack =
                "RootTask id=14 displayId=8\n"
                        + " configuration={mWindowingMode=fullscreen}\n"
                        + " taskId=24: io.github.mekhontsev.magicdesk/"
                        + ".DesktopChromeActivity "
                        + "topActivity=ComponentInfo{"
                        + "io.github.mekhontsev.magicdesk/"
                        + ".DesktopChromeActivity} visible=true\n"
                        + "RootTask id=10 displayId=8\n"
                        + " configuration={mWindowingMode=fullscreen}\n"
                        + " taskId=20: io.github.mekhontsev.magicdesk/"
                        + ".DesktopActivity topActivity=ComponentInfo{"
                        + "io.github.mekhontsev.magicdesk/.DesktopActivity} "
                        + "visible=true\n";

        assertEquals(20, DesktopSelfTestTasks.findFrontTask(
                stack, 8).taskId);
    }

}
