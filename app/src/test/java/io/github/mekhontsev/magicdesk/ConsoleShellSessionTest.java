package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ConsoleShellSessionTest {
    private static final String MARKER = "__MAGICDESK_CWD_test__";

    @Test
    public void runsInCurrentDirectoryAndTracksChangedDirectory()
            throws Exception {
        final ConsoleShellSession session = new ConsoleShellSession(
                "/storage/emulated/0/Dmitry's files",
                command -> {
                    assertTrue(command.startsWith(
                            "cd -- '/storage/emulated/0/Dmitry'\"'\"'s files'"
                                    + " || exit $?\n"));
                    assertTrue(command.contains("cd ../Desktop && pwd\n"));
                    return result(0, "/storage/emulated/0/Desktop");
                },
                "test");

        final ConsoleShellSession.ExecutionResult result =
                session.execute("cd ../Desktop && pwd");

        assertEquals(0, result.exitCode);
        assertEquals("/storage/emulated/0/Desktop\n", result.output);
        assertEquals("/storage/emulated/0/Desktop", result.workingDirectory);
        assertEquals("/storage/emulated/0/Desktop", session.workingDirectory());
    }

    @Test
    public void keepsTrailingNewlineFromUserOutput() throws Exception {
        final ConsoleShellSession session = sessionReturning(
                new ShellAccess.CommandResult(
                        0,
                        "one\ntwo\n\n" + MARKER + "/tmp\n"));

        final ConsoleShellSession.ExecutionResult result =
                session.execute("printf 'one\\ntwo\\n'");

        assertEquals("one\ntwo\n", result.output);
        assertEquals("/tmp", result.workingDirectory);
    }

    @Test
    public void keepsDirectoryAndOutputWhenCommandExitsBeforeMarker()
            throws Exception {
        final ConsoleShellSession session = sessionReturning(
                new ShellAccess.CommandResult(4, "stopped\n"));

        final ConsoleShellSession.ExecutionResult result =
                session.execute("echo stopped; exit 4");

        assertEquals(4, result.exitCode);
        assertEquals("stopped\n", result.output);
        assertEquals("/tmp", result.workingDirectory);
    }

    @Test
    public void rejectsRelativeWorkingDirectory() {
        try {
            new ConsoleShellSession("relative", command -> null, "test");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("absolute"));
            return;
        }
        throw new AssertionError("relative working directory was accepted");
    }

    @Test
    public void serviceMarkerIsNotPartOfVisibleOutput() throws Exception {
        final ConsoleShellSession session = sessionReturning(result(0, "/tmp"));

        final String output = session.execute("pwd").output;

        assertFalse(output.contains("MAGICDESK_CWD"));
    }

    private static ConsoleShellSession sessionReturning(
            final ShellAccess.CommandResult result) {
        return new ConsoleShellSession("/tmp", command -> result, "test");
    }

    private static ShellAccess.CommandResult result(
            final int exitCode, final String directory) {
        return new ShellAccess.CommandResult(
                exitCode,
                directory + "\n\n" + MARKER + directory + "\n");
    }
}
