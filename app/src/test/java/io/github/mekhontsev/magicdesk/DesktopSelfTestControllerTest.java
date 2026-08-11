package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

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
}
