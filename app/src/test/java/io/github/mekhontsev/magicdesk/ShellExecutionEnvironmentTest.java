package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import org.junit.Rule;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

public final class ShellExecutionEnvironmentTest {
    @Rule public final TestFileSystem files = TestFileSystem.unix();

    @Test
    public void interactiveEnvironmentIsStableAndSanitized() {
        final Map<String, String> environment = new HashMap<>();
        environment.put("TERMUX_VERSION", "test");
        environment.put("SHELL_CMD__PACKAGE_NAME", "com.termux");
        environment.put("PREFIX", "/termux");
        environment.put("PWD", "/termux/home");
        environment.put("LD_PRELOAD", "/unexpected.so");
        environment.put("ENV", "/termux/shellrc");
        environment.put("HISTFILE", "/termux/.bash_history");
        environment.put("HISTSIZE", "99999");
        environment.put("SHELL", "/unexpected/shell");
        environment.put("EXECSHELL", "/unexpected/script-shell");

        ShellExecutionEnvironment.apply(
                environment, ShellAccess.SHELL_UID, true, new RuntimeDataPaths(files.path("/home/shell")), "/apk/libmagicdesk_mksh.so");

        assertEquals("/home/shell", environment.get("HOME"));
        assertEquals("/home/shell/cache/tmp", environment.get("TMPDIR"));
        assertEquals("xterm-256color", environment.get("TERM"));
        assertEquals("truecolor", environment.get("COLORTERM"));
        assertEquals("/home/shell/files/config/shellrc", environment.get("ENV"));
        assertEquals("/home/shell/files/share", environment.get("XDG_DATA_HOME"));
        assertEquals("/home/shell/cache", environment.get("XDG_CACHE_HOME"));
        assertEquals("/home/shell/files/state", environment.get("XDG_STATE_HOME"));
        assertEquals("/home/shell/no_backup/runtime", environment.get("MAGICDESK_RUNTIME"));
        assertEquals("/home/shell/linux", environment.get("MAGICDESK_GUEST_HOME"));
        assertEquals("shell", environment.get("USER"));
        assertTrue(environment.get("PATH").startsWith(
                "/home/shell/files/bin:/system/bin"));
        assertFalse(environment.containsKey("TERMUX_VERSION"));
        assertFalse(environment.containsKey("SHELL_CMD__PACKAGE_NAME"));
        assertFalse(environment.containsKey("PREFIX"));
        assertFalse(environment.containsKey("PWD"));
        assertFalse(environment.containsKey("LD_PRELOAD"));
        assertFalse(environment.containsKey("HISTFILE"));
        assertFalse(environment.containsKey("HISTSIZE"));
        assertFalse(environment.containsKey("EXECSHELL"));
        assertEquals("/apk/libmagicdesk_mksh.so", environment.get("SHELL"));
        assertEquals(environment.get("SHELL"), environment.get(AndroidShell.PATH_ENV));
    }

    @Test
    public void nonInteractiveRootUsesTheSameRuntimeContract() {
        final Map<String, String> environment = new HashMap<>();
        environment.put("COLORTERM", "inherited");
        environment.put("ENV", "/inherited/shellrc");
        environment.put("HISTFILE", "/inherited/history");
        environment.put("HISTSIZE", "99999");

        ShellExecutionEnvironment.apply(
                environment, ShellAccess.ROOT_UID, false, new RuntimeDataPaths(files.path("/runtime/root")), "/apk/libmagicdesk_mksh.so");

        assertEquals("root", environment.get("USER"));
        assertEquals("dumb", environment.get("TERM"));
        assertFalse(environment.containsKey("COLORTERM"));
        assertFalse(environment.containsKey("ENV"));
        assertFalse(environment.containsKey("HISTFILE"));
        assertFalse(environment.containsKey("HISTSIZE"));
        assertEquals("/apk/libmagicdesk_mksh.so", environment.get("SHELL"));
        assertEquals("/runtime/root/files/bin", environment.get("MAGICDESK_TOOLS"));
    }

    @Test
    public void diagnosticsDescribeAndroidPrivilegedRoots() {
        // Diagnostics intentionally use the fixed Android paths, not a test root.
        assumeTrue("Android root paths require a Unix host", RuntimeDataPaths.SHELL_ROOT.isAbsolute());
        assertTrue(ShellExecutionEnvironment.diagnostics(ShellAccess.ROOT_UID)
                .contains("/data/local/tmp/magicdesk/root"));
        assertTrue(ShellExecutionEnvironment.diagnostics(ShellAccess.SHELL_UID)
                .contains("home=/data/local/tmp/magicdesk,"));
        assertTrue(ShellExecutionEnvironment.diagnostics(ShellAccess.SHELL_UID)
                .contains("tools=/data/local/tmp/magicdesk/files/bin"));
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
        assertTrue(shell.contains("HISTSIZE=2000"));
        assertTrue(shell.contains("HISTFILE=${XDG_STATE_HOME:-$HOME/.local/state}/shell_history"));
        assertTrue(shell.contains("typeset +x HISTFILE HISTSIZE"));
    }

    @Test
    public void androidShellRequiresThePackagedExecutableWithoutSystemFallback() throws Exception {
        final Path directory = Files.createTempDirectory("magicdesk-shell-test");
        final Path shell = directory.resolve("libmagicdesk_mksh.so");
        try {
            assertThrows(java.io.IOException.class,
                    () -> AndroidShell.checkExecutable(shell));
            Files.createDirectory(shell);
            assertThrows(java.io.IOException.class,
                    () -> AndroidShell.checkExecutable(shell));
            Files.delete(shell);
            Files.writeString(shell, "fixture");
            assertTrue(shell.toFile().setExecutable(true));
            AndroidShell.checkExecutable(shell);
        } finally {
            Files.deleteIfExists(shell);
            Files.delete(directory);
        }
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
