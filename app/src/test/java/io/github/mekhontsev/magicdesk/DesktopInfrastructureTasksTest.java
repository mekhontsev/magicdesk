package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class DesktopInfrastructureTasksTest {
    @Test
    public void classifiesDesktopChromeAndBackstop() {
        assertTrue(DesktopInfrastructureTasks.isComponentName(
                componentName("DesktopChromeActivity")));
        assertTrue(DesktopInfrastructureTasks.isComponentName(
                componentName("TaskAreaBackstopActivity")));
    }

    @Test
    public void doesNotClassifyDesktopHostOrApplication() {
        assertFalse(DesktopInfrastructureTasks.isComponentName(
                componentName("DiagnosticsActivity")));
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

    @Test
    public void onlyHomeInAnAuxiliaryAreaIsInfrastructure() {
        final String home = componentName("PhoneHomeActivity");
        assertTrue(DesktopInfrastructureTasks.isAuxiliaryHome(true, 20001, home, home));
        assertFalse(DesktopInfrastructureTasks.isAuxiliaryHome(true, 1, home, home));
        assertFalse(DesktopInfrastructureTasks.isAuxiliaryHome(true, -1, home, home));
        assertFalse(DesktopInfrastructureTasks.isAuxiliaryHome(false, 20001, home, home));
        assertFalse(DesktopInfrastructureTasks.isAuxiliaryHome(true, 20001,
                "example/.Home", "example/.Home"));
        assertFalse(DesktopInfrastructureTasks.isAuxiliaryHome(false, 20001,
                componentName("FileManagerActivity"), componentName("FileManagerActivity")));
    }
}
