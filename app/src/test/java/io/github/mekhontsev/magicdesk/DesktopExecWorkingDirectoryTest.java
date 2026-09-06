package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class DesktopExecWorkingDirectoryTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void absentDirectoryLeavesScriptUnchanged() {
        final String command = "printf 'one'; printf 'two'\nexit 7";
        assertEquals(command, DesktopExecWorkingDirectory.shellCommand(command, null));
        assertEquals(command, DesktopExecWorkingDirectory.shellCommand(command, ""));
    }

    @Test
    public void shellPreparationUsesSharedDirectoryValidation() {
        for (final String invalid : new String[]{"relative", "/tmp\nnext", "/tmp\rnext",
                "/tmp\0next", "/" + "x".repeat(4096)}) {
            assertThrows(IllegalArgumentException.class,
                    () -> DesktopExecWorkingDirectory.shellCommand("pwd", invalid));
        }
    }

    @Test
    public void missingDirectoryPreventsEveryCommandInAList() throws Exception {
        assertFailedDirectoryStopsCommand("printf first; ");
    }

    @Test
    public void missingDirectoryDoesNotRunUserErrorBranch() throws Exception {
        assertFailedDirectoryStopsCommand("false || ");
    }

    @Test
    public void missingDirectoryPreventsLaterScriptLines() throws Exception {
        assertFailedDirectoryStopsCommand("printf first\n");
    }

    @Test
    public void successfulDirectoryChangePreservesQuotingScriptAndExitStatus() throws Exception {
        final String directory = temporary.newFolder("space ' quote").getAbsolutePath();
        final var result = run(DesktopExecWorkingDirectory.shellCommand(
                "printf '%s\\n' \"$PWD\"\ncat <<'END'\nraw $text; 'quoted'\nEND\nexit 7",
                directory));
        assertEquals(7, result.exitCode);
        assertEquals(directory + "\nraw $text; 'quoted'\n", result.output);
    }

    private void assertFailedDirectoryStopsCommand(final String prefix) throws Exception {
        final Path marker = temporary.getRoot().toPath().resolve("executed");
        final Path missing = temporary.getRoot().toPath().resolve("missing");
        final String command = prefix + "printf bad > " + ShellCommandLine.quote(marker.toString());
        final var result = run(DesktopExecWorkingDirectory.shellCommand(command, missing.toString()));
        assertFalse("a command ran outside its requested directory", Files.exists(marker));
        assertNotEquals(result.output, 0, result.exitCode);
    }

    private static BoundedProcessRunner.Result run(final String command) throws Exception {
        // Execute the prepared script on the host, without Android or a desktop session.
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"));
        final String prefix = System.getenv("PREFIX");
        final Path shell = prefix == null ? Path.of("/bin/sh") : Path.of(prefix, "bin", "sh");
        assertTrue(Files.isExecutable(shell));
        return BoundedProcessRunner.run(new ProcessBuilder(shell.toString(), "-c", command)
                .redirectErrorStream(true).start(), 3_000L, 8192);
    }
}
