package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

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
