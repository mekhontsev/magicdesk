package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NubiaTouchpadControllerTest {
    private static final String HEADER =
            "RootTask id=2927 bounds=[0,0][1216,2688] displayId=0 userId=0\n"
                    + " configuration={winConfig={ mWindowingMode=fullscreen"
                    + " mActivityType=standard}}\n";
    private static final String SHORT_TASK =
            "  taskId=2927: cn.nubia.keymapcenter/.mirror.MirrorInputActivity"
                    + " bounds=[0,0][1216,2688] userId=0 %s"
                    + " topActivity=ComponentInfo{cn.nubia.keymapcenter/"
                    + ".mirror.MirrorInputActivity}\n";
    private static final String FULL_TASK =
            "  taskId=2927: cn.nubia.keymapcenter/"
                    + "cn.nubia.keymapcenter.mirror.MirrorInputActivity"
                    + " bounds=[0,0][1216,2688] userId=0 %s"
                    + " topActivity=ComponentInfo{cn.nubia.keymapcenter/"
                    + "cn.nubia.keymapcenter.mirror.MirrorInputActivity}\n";

    @Test
    public void visibleTouchpadIsDetected() {
        assertTrue(NubiaTouchpadController.isActivityVisible(
                HEADER + String.format(SHORT_TASK, "visible=true")));
        assertTrue(NubiaTouchpadController.isActivityVisible(
                HEADER + String.format(FULL_TASK, "visible=true")));
    }

    @Test
    public void stoppedTouchpadInTaskHistoryIsNotVisible() {
        assertFalse(NubiaTouchpadController.isActivityVisible(
                HEADER + String.format(FULL_TASK, "visible=false")));
    }

    @Test
    public void unrelatedVisibleTaskIsIgnored() {
        assertFalse(NubiaTouchpadController.isActivityVisible(
                HEADER
                        + "  taskId=2927: com.android.launcher3/"
                        + "com.android.launcher3.uioverrides.QuickstepLauncher"
                        + " bounds=[0,0][1216,2688] userId=0 visible=true\n"));
    }
}
