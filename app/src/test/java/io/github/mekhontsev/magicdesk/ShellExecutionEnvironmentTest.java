package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

public final class ShellExecutionEnvironmentTest {
    @Test
    public void interactiveEnvironmentIsStableAndSanitized() {
        final Map<String, String> environment = new HashMap<>();
        environment.put("TERMUX_VERSION", "test");
        environment.put("SHELL_CMD__PACKAGE_NAME", "com.termux");
        environment.put("PREFIX", "/termux");
        environment.put("PWD", "/termux/home");
        environment.put("LD_PRELOAD", "/unexpected.so");
        environment.put("ENV", "/termux/shellrc");

        ShellExecutionEnvironment.apply(
                environment, ShellAccess.SHELL_UID, true, "/runtime/shell");

        assertEquals("/runtime/shell/home", environment.get("HOME"));
        assertEquals("/runtime/shell/tmp", environment.get("TMPDIR"));
        assertEquals("xterm-256color", environment.get("TERM"));
        assertEquals("truecolor", environment.get("COLORTERM"));
        assertEquals("/runtime/shell/home/.config/shellrc", environment.get("ENV"));
        assertEquals("shell", environment.get("USER"));
        assertTrue(environment.get("PATH").startsWith(
                "/runtime/shell/bin:/system/bin"));
        assertFalse(environment.containsKey("TERMUX_VERSION"));
        assertFalse(environment.containsKey("SHELL_CMD__PACKAGE_NAME"));
        assertFalse(environment.containsKey("PREFIX"));
        assertFalse(environment.containsKey("PWD"));
        assertFalse(environment.containsKey("LD_PRELOAD"));
    }

    @Test
    public void nonInteractiveRootUsesTheSameRuntimeContract() {
        final Map<String, String> environment = new HashMap<>();
        environment.put("COLORTERM", "inherited");
        environment.put("ENV", "/inherited/shellrc");

        ShellExecutionEnvironment.apply(
                environment, ShellAccess.ROOT_UID, false, "/runtime/root");

        assertEquals("root", environment.get("USER"));
        assertEquals("dumb", environment.get("TERM"));
        assertFalse(environment.containsKey("COLORTERM"));
        assertFalse(environment.containsKey("ENV"));
        assertEquals("/runtime/root/bin", environment.get("MAGICDESK_TOOLS"));
        assertTrue(ShellExecutionEnvironment.diagnostics(ShellAccess.ROOT_UID)
                .contains("/data/local/tmp/magicdesk-root/home"));
        assertTrue(ShellExecutionEnvironment.diagnostics(ShellAccess.SHELL_UID)
                .contains("/data/local/tmp/magicdesk-shell/home"));
    }

    @Test
    public void interactivePromptUsesTitleAndActualServiceIdentity() {
        final String shell = ShellExecutionEnvironment.interactiveShellStartup(ShellAccess.SHELL_UID);
        final String root = ShellExecutionEnvironment.interactiveShellStartup(ShellAccess.ROOT_UID);
        assertTrue(shell.contains("local status=$?"));
        assertTrue(shell.contains("(( status )) && REPLY+=\"[exit $status] \""));
        assertTrue(shell.contains("REPLY+=\"$title \"'$ '"));
        assertTrue(root.contains("REPLY+=\"$title \"'# '"));
        assertTrue(shell.contains("\\e]0;"));
        assertTrue(shell.contains("\\e]133;B"));
        assertFalse(shell.contains("\\e]133;C"));
        assertFalse(shell.contains("echo"));
    }

    @Test
    public void interactiveStartupIsOwnedAndReplacedOnlyWhenChanged() throws Exception {
        final Path directory = Files.createTempDirectory("magicdesk-shellrc-test");
        final Path target = directory.resolve("shellrc");
        try {
            ShellExecutionEnvironment.prepareInteractiveShell(target, ShellAccess.SHELL_UID);
            final String expected = ShellExecutionEnvironment.interactiveShellStartup(ShellAccess.SHELL_UID);
            assertEquals(expected, Files.readString(target));
            final var timestamp = java.nio.file.attribute.FileTime.fromMillis(1_000);
            Files.setLastModifiedTime(target, timestamp);
            ShellExecutionEnvironment.prepareInteractiveShell(target, ShellAccess.SHELL_UID);
            assertEquals(timestamp, Files.getLastModifiedTime(target));
            Files.write(target, "stale".getBytes(StandardCharsets.UTF_8));
            ShellExecutionEnvironment.prepareInteractiveShell(target, ShellAccess.ROOT_UID);
            assertEquals(ShellExecutionEnvironment.interactiveShellStartup(ShellAccess.ROOT_UID),
                    Files.readString(target));
            try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
        } finally {
            Files.deleteIfExists(target);
            Files.delete(directory);
        }
    }
}
