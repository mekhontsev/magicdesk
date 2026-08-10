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
    public void recognizesPreparedFreeformAnchorRegardlessOfVisibilityHint() {
        final TaskStackParser.Entry invisible = TaskStackParser.parse(
                "RootTask id=12 displayId=8\n"
                        + " configuration={mWindowingMode=freeform}\n"
                        + " taskId=22: io.github.mekhontsev.magicdesk/"
                        + ".FreeformLaunchAnchorActivity "
                        + "topActivity=ComponentInfo{"
                        + "io.github.mekhontsev.magicdesk/"
                        + ".FreeformLaunchAnchorActivity} "
                        + "visible=false bounds=[1700,860][1920,1080]\n")
                .get(0);
        final TaskStackParser.Entry visible = TaskStackParser.parse(
                "RootTask id=12 displayId=8\n"
                        + " configuration={mWindowingMode=freeform}\n"
                        + " taskId=22: io.github.mekhontsev.magicdesk/"
                        + ".FreeformLaunchAnchorActivity "
                        + "topActivity=ComponentInfo{"
                        + "io.github.mekhontsev.magicdesk/"
                        + ".FreeformLaunchAnchorActivity} "
                        + "visible=true bounds=[1872,1048][2092,1268]\n")
                .get(0);
        final TaskStackParser.Entry fullscreen = TaskStackParser.parse(
                "RootTask id=12 displayId=8\n"
                        + " configuration={mWindowingMode=fullscreen}\n"
                        + " taskId=22: io.github.mekhontsev.magicdesk/"
                        + ".FreeformLaunchAnchorActivity "
                        + "topActivity=ComponentInfo{"
                        + "io.github.mekhontsev.magicdesk/"
                        + ".FreeformLaunchAnchorActivity} "
                        + "visible=false bounds=[0,0][1920,1080]\n")
                .get(0);

        assertTrue(DesktopSelfTestController.isReadyAnchorTask(invisible));
        assertTrue(DesktopSelfTestController.isReadyAnchorTask(visible));
        assertFalse(DesktopSelfTestController.isReadyAnchorTask(fullscreen));
    }
}
