package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopInfrastructureTasksTest {
    @Test
    public void classifiesDesktopChromeAndBackstop() {
        assertTrue(DesktopInfrastructureTasks.isComponentName(
                componentName("DesktopTaskbarActivity")));
        assertTrue(DesktopInfrastructureTasks.isComponentName(
                componentName("DesktopPanelActivity")));
        assertTrue(DesktopInfrastructureTasks.isComponentName(
                componentName("DesktopSelfTestPhoneGuardActivity")));
        assertTrue(DesktopInfrastructureTasks.isComponentName(
                componentName("TaskAreaBackstopActivity")));
    }

    @Test
    public void doesNotClassifyDesktopHostOrApplication() {
        assertFalse(DesktopInfrastructureTasks.isComponentName(
                componentName("DesktopActivity")));
        assertFalse(DesktopInfrastructureTasks.isComponentName(
                "org.example/org.example.MainActivity"));
        assertFalse(DesktopInfrastructureTasks.isComponentName(null));
    }

    private static String componentName(final String className) {
        return BuildConfig.APPLICATION_ID + "/."
                + className;
    }
}
