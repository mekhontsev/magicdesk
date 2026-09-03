package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

public final class TaskControlCommandTest {
    @Test
    public void shellUsesThePackageOwnedByUid2000() {
        assertEquals(
                "com.android.shell",
                TaskControlCommand.callingPackageForUid(2000));
    }

    @Test
    public void appAndRootUseTheMagicDeskPackage() {
        assertEquals(
                "io.github.mekhontsev.magicdesk",
                TaskControlCommand.callingPackageForUid(0));
        assertEquals(
                "io.github.mekhontsev.magicdesk",
                TaskControlCommand.callingPackageForUid(10615));
    }

    @Test
    public void moveTaskResolverPrefersCurrentFiveArgumentSignature()
            throws Exception {
        final Method method = TaskControlCommand.findMoveTaskToFrontMethod(
                SupportedOverloads.class);

        assertEquals(5, method.getParameterCount());
        assertEquals(String.class, method.getParameterTypes()[1]);
        assertEquals(Integer.TYPE, method.getParameterTypes()[2]);
    }

    @Test
    public void moveTaskResolverRejectsUnknownOverload() {
        assertThrows(NoSuchMethodException.class, () ->
                TaskControlCommand.findMoveTaskToFrontMethod(
                        UnsupportedOverload.class));
    }

    @Test
    public void desktopHostClassesIncludePhoneAndExternalHome() {
        assertTrue(DesktopHostComponents.isHostClassName(
                "io.github.mekhontsev.magicdesk.DesktopActivity"));
        assertTrue(DesktopHostComponents.isHostClassName(
                "io.github.mekhontsev.magicdesk.PhoneDesktopHomeActivity"));
        assertFalse(DesktopHostComponents.isHostClassName(
                "io.github.mekhontsev.magicdesk.PhoneHomeActivity"));
    }

    @Test
    public void flattenedHostNamesRequireTheMagicDeskPackage() {
        assertTrue(DesktopHostComponents.isHostComponentName(
                "io.github.mekhontsev.magicdesk/.PhoneDesktopHomeActivity"));
        assertTrue(DesktopHostComponents.isHostComponentName(
                "io.github.mekhontsev.magicdesk/"
                        + "io.github.mekhontsev.magicdesk.DesktopActivity"));
        assertFalse(DesktopHostComponents.isHostComponentName(
                "com.example/.PhoneDesktopHomeActivity"));
    }

    public static final class SupportedOverloads {
        public void moveTaskToFront(
                final Object caller,
                final String packageName,
                final int taskId,
                final int flags) {
        }

        public void moveTaskToFront(
                final Object caller,
                final String packageName,
                final int taskId,
                final int flags,
                final Object options) {
        }

        public void moveTaskToFront(final int ambiguous) {
        }
    }

    public static final class UnsupportedOverload {
        public void moveTaskToFront(final int ambiguous) {
        }
    }
}
