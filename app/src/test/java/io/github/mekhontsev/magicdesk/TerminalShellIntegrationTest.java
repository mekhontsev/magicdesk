package io.github.mekhontsev.magicdesk;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public final class TerminalShellIntegrationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void bashPreservesUserHooksStatusAndPromptWithoutGrowingWrappers() throws Exception {
        final String prefix = System.getenv("PREFIX");
        final Path bash = prefix == null ? Path.of("/bin/bash") : Path.of(prefix, "bin/bash");
        assumeTrue(Files.isExecutable(bash));
        final Path home = temporary.getRoot().toPath();
        Files.writeString(home.resolve(".bashrc"), """
                PS1='test$ '
                PROMPT_COMMAND=('printf "HOOK:%s\\n" "$?"')
                """);
        final Path rc = home.resolve("rc");
        Files.writeString(rc, TerminalShellIntegration.BASH_RC);
        final ProcessBuilder builder = new ProcessBuilder(bash.toString(), "--rcfile", rc.toString(), "-i")
                .redirectErrorStream(true);
        builder.environment().put("HOME", home.toString());
        builder.environment().put("PREFIX", home.toString());
        final Process process = builder.start();
        process.getOutputStream().write("false\nprintf 'VALUE:%s\\n' \"$?\"\nexit\n".getBytes(StandardCharsets.UTF_8));
        final var result = BoundedProcessRunner.run(process, 5_000, 32_768);
        assertTrue(result.output, result.output.contains("\u001b]133;C\u0007"));
        assertTrue(result.output, result.output.contains("\u001b]133;D;1\u0007"));
        assertTrue(result.output, result.output.contains("HOOK:1"));
        assertTrue(result.output, result.output.contains("VALUE:1"));
        assertFalse(result.output, result.output.contains("\u001b]133;A\u0007\u001b]133;A\u0007"));
        assertTrue(Files.readString(home.resolve(".bashrc")).startsWith("PS1='test$ '"));
    }

    @Test public void termuxLoginResolvesUserShellWithoutOverridingExplicitShells() throws Exception {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"));
        final String prefix = System.getenv("PREFIX");
        final Path bash = prefix == null ? Path.of("/bin/bash") : Path.of(prefix, "bin/bash");
        assumeTrue(Files.isExecutable(bash));
        final Path home = temporary.getRoot().toPath();
        assertEquals("/example/prefix/bin/bash", resolveShell(bash, home, "/example/prefix/bin/login"));
        Files.createDirectories(home.resolve(".termux"));
        Files.createSymbolicLink(home.resolve(".termux/shell"), bash);
        assertEquals(bash.toRealPath().toString(), resolveShell(bash, home, "/example/prefix/bin/login"));
        assertEquals("/custom/zsh", resolveShell(bash, home, "/custom/zsh"));
    }

    private static String resolveShell(final Path bash, final Path home, final String shell) throws Exception {
        final ProcessBuilder builder = new ProcessBuilder(bash.toString(), "-c",
                TerminalShellIntegration.termuxShellSelection() + "printf '%s' \"$SHELL\"").redirectErrorStream(true);
        builder.environment().put("HOME", home.toString());
        builder.environment().put("PREFIX", "/example/prefix");
        builder.environment().put("SHELL", shell);
        final var result = BoundedProcessRunner.run(builder.start(), 5_000, 8192);
        assertEquals(result.output, 0, result.exitCode);
        return result.output;
    }
}
