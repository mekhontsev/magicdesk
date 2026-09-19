package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class TaskControlCommandTest {
    @Test
    public void desktopHostClassesIncludePhoneAndExternalHome() {
        assertTrue(DesktopHostComponents.isHostClassName(
                "io.github.mekhontsev.magicdesk.DesktopActivity"));
        assertTrue(DesktopHostComponents.isHostClassName(
                "io.github.mekhontsev.magicdesk.PhoneHomeActivity"));
        assertFalse(DesktopHostComponents.isHostClassName(
                "io.github.mekhontsev.magicdesk.ControlActivity"));
    }

    @Test
    public void flattenedHostNamesRequireTheMagicDeskPackage() {
        assertTrue(DesktopHostComponents.isHostComponentName(
                "io.github.mekhontsev.magicdesk/.PhoneHomeActivity"));
        assertTrue(DesktopHostComponents.isHostComponentName(
                "io.github.mekhontsev.magicdesk/"
                        + "io.github.mekhontsev.magicdesk.DesktopActivity"));
        assertFalse(DesktopHostComponents.isHostComponentName(
                "com.example/.PhoneHomeActivity"));
    }

}
