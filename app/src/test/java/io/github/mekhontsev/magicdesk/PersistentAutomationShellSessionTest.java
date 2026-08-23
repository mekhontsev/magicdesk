package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public final class PersistentAutomationShellSessionTest {
    private static final String MARKER = "__MAGICDESK_CWD_test__0\t";

    @Test
    public void runsInCurrentDirectoryAndTracksChangedDirectory()
            throws Exception {
        final PersistentAutomationShellSession session = new PersistentAutomationShellSession(
                "/storage/emulated/0/Dmitry's files",
                command -> {
                    assertTrue(command.startsWith(
                            "__magicdesk_status_test=0\n"
                                    + "cd -- '/storage/emulated/0/Dmitry'"
                                    + "\"'\"'s files'"
                                    + " || __magicdesk_status_test=$?\n"));
                    assertTrue(command.contains("cd ../Desktop && pwd\n"));
                    return result(0, "/storage/emulated/0/Desktop");
                },
                "test");

        final PersistentAutomationShellSession.ExecutionResult result =
                session.execute("cd ../Desktop && pwd");

        assertEquals(0, result.exitCode);
        assertEquals("/storage/emulated/0/Desktop\n", result.output);
        assertEquals("/storage/emulated/0/Desktop", result.workingDirectory);
        assertEquals("/storage/emulated/0/Desktop", session.workingDirectory());
    }

    @Test
    public void keepsTrailingNewlineFromUserOutput() throws Exception {
        final PersistentAutomationShellSession session = sessionReturning(
                new ShellAccess.CommandResult(
                        0,
                        "one\ntwo\n\n" + MARKER + "/tmp\n"));

        final PersistentAutomationShellSession.ExecutionResult result =
                session.execute("printf 'one\\ntwo\\n'");

        assertEquals("one\ntwo\n", result.output);
        assertEquals("/tmp", result.workingDirectory);
    }

    @Test
    public void appliesDirectoryOnlyWhenPersistentShellNeedsIt()
            throws Exception {
        final int[] execution = { 0 };
        final PersistentAutomationShellSession session = new PersistentAutomationShellSession(
                "/tmp",
                command -> {
                    if (execution[0]++ == 0) {
                        assertTrue(command.contains("cd -- '/tmp'"));
                    } else {
                        assertFalse(command.contains("cd -- "));
                    }
                    return result(0, "/tmp");
                },
                "test");

        session.execute("export VALUE=kept");
        session.execute("printenv VALUE");

        assertEquals(2, execution[0]);
    }

    @Test
    public void explicitDirectoryChangeIsAppliedOnce() throws Exception {
        final int[] execution = { 0 };
        final PersistentAutomationShellSession session = new PersistentAutomationShellSession(
                "/tmp",
                command -> {
                    execution[0]++;
                    if (execution[0] == 2) {
                        assertTrue(command.contains("cd -- '/sdcard'"));
                    } else if (execution[0] == 3) {
                        assertFalse(command.contains("cd -- "));
                    }
                    return result(0, execution[0] >= 2 ? "/sdcard" : "/tmp");
                },
                "test");

        session.execute("pwd");
        session.setWorkingDirectory("/sdcard");
        session.execute("pwd");
        session.execute("pwd");

        assertEquals(3, execution[0]);
    }

    @Test
    public void keepsDirectoryAndOutputWhenCommandExitsBeforeMarker()
            throws Exception {
        final PersistentAutomationShellSession session = sessionReturning(
                new ShellAccess.CommandResult(4, "stopped\n"));

        final PersistentAutomationShellSession.ExecutionResult result =
                session.execute("echo stopped; exit 4");

        assertEquals(4, result.exitCode);
        assertEquals("stopped\n", result.output);
        assertEquals("/tmp", result.workingDirectory);
    }

    @Test
    public void recognizesCommandsThatCloseConsole() {
        assertTrue(PersistentAutomationShellSession.isExitCommand("exit"));
        assertTrue(PersistentAutomationShellSession.isExitCommand("  exit 4  "));
        assertFalse(PersistentAutomationShellSession.isExitCommand("echo exit"));
        assertFalse(PersistentAutomationShellSession.isExitCommand("exit invalid"));
    }

    @Test
    public void failedShellReappliesLastConfirmedDirectory() throws Exception {
        final int[] execution = { 0 };
        final PersistentAutomationShellSession session = new PersistentAutomationShellSession(
                "/tmp",
                command -> {
                    execution[0]++;
                    assertTrue(command.contains("cd -- '/tmp'"));
                    if (execution[0] == 1) {
                        throw new java.io.IOException("stream ended");
                    }
                    return result(0, "/tmp");
                },
                "test");

        try {
            session.execute("pwd");
        } catch (java.io.IOException expected) {
            assertEquals("stream ended", expected.getMessage());
        }
        session.execute("pwd");

        assertEquals(2, execution[0]);
    }

    @Test
    public void rejectsRelativeWorkingDirectory() {
        try {
            new PersistentAutomationShellSession("relative", command -> null, "test");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("absolute"));
            return;
        }
        throw new AssertionError("relative working directory was accepted");
    }

    @Test
    public void serviceMarkerIsNotPartOfVisibleOutput() throws Exception {
        final PersistentAutomationShellSession session = sessionReturning(result(0, "/tmp"));

        final String output = session.execute("pwd").output;

        assertFalse(output.contains("MAGICDESK_CWD"));
    }

    @Test
    public void fallsBackToCompletedOutputWhenExecutorCannotStream()
            throws Exception {
        final PersistentAutomationShellSession session = sessionReturning(
                result(0, "/tmp"));
        final StringBuilder streamed = new StringBuilder();

        session.execute("pwd", streamed::append);

        assertEquals("/tmp\n", streamed.toString());
    }

    @Test
    public void readsAndNormalizesThePersistentShellPath() throws Exception {
        final PersistentAutomationShellSession session = new PersistentAutomationShellSession(
                "/tmp",
                command -> {
                    assertTrue(command.contains("\"$PATH\""));
                    return new ShellAccess.CommandResult(
                            0,
                            "/system/bin:/vendor/bin:."
                                    + "\n" + MARKER + "/tmp\n");
                },
                "test");

        final List<String> paths = session.commandSearchPath();

        assertEquals(Arrays.asList(
                "/system/bin", "/vendor/bin", "/tmp"), paths);
    }

    @Test
    public void cancelIsDelegatedAndReappliesTheWorkingDirectory()
            throws Exception {
        final boolean[] cancelled = { false };
        final int[] execution = { 0 };
        final PersistentAutomationShellSession.CommandExecutor executor =
                new PersistentAutomationShellSession.CommandExecutor() {
                    @Override
                    public ShellAccess.CommandResult execute(
                            final String command) {
                        execution[0]++;
                        if (execution[0] == 2) {
                            assertTrue(command.contains("cd -- '/tmp'"));
                        }
                        return result(0, "/tmp");
                    }

                    @Override
                    public void cancelCurrent() {
                        cancelled[0] = true;
                    }
                };
        final PersistentAutomationShellSession session = new PersistentAutomationShellSession(
                "/tmp", executor, "test");
        session.execute("pwd");

        session.cancelCurrentCommand();
        session.execute("pwd");

        assertTrue(cancelled[0]);
    }

    private static PersistentAutomationShellSession sessionReturning(
            final ShellAccess.CommandResult result) {
        return new PersistentAutomationShellSession("/tmp", command -> result, "test");
    }

    private static ShellAccess.CommandResult result(
            final int exitCode, final String directory) {
        return new ShellAccess.CommandResult(
                exitCode,
                directory + "\n\n" + MARKER + directory + "\n");
    }
}
