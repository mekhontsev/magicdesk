package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class AppProcessCommandTest {
    @Test
    public void commandResolvesInstalledApkAndArguments() {
        final String command = AppProcessCommand.run(
                "io.github.mekhontsev.magicdesk.TestCommand",
                "one two");

        assertTrue(command.contains(
                "pm path io.github.mekhontsev.magicdesk"));
        assertTrue(command.contains(
                "CLASSPATH=\"$APK\" /system/bin/app_process / "
                        + "io.github.mekhontsev.magicdesk.TestCommand one two"));
        assertFalse(command.contains("  one"));
    }

    @Test
    public void execUsesShellReplacement() {
        final String command = AppProcessCommand.exec(
                "io.github.mekhontsev.magicdesk.Watcher", "");

        assertTrue(command.contains("export CLASSPATH=\"$APK\""));
        assertTrue(command.endsWith(
                "exec /system/bin/app_process / "
                        + "io.github.mekhontsev.magicdesk.Watcher"));
    }
}
