package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import org.junit.Test;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class PersistentAutomationShellSessionTest {
    @Test public void directoryEnvironmentAndBinarySinkShareOneExecutor() throws Exception {
        final List<String> requests = new ArrayList<>();
        final var sink = new java.io.ByteArrayOutputStream();
        final var session = new PersistentAutomationShellSession("/tmp", (command, stdout) -> {
            requests.add(command);
            if (stdout != null) stdout.write(new byte[]{0, (byte) 255}, 0, 2);
            return result("/sdcard", 7);
        }, "test");
        session.execute("export X=kept; cd /sdcard");
        final var result = session.execute("printf \"$X\"", sink::write);
        assertTrue(requests.get(0).contains("cd -- '/tmp'"));
        assertFalse(requests.get(1).contains("cd -- "));
        assertTrue(requests.get(1).contains("printf \"$X\""));
        assertTrue(requests.get(0).contains("} 2>&1\n"));
        assertFalse(requests.get(1).contains("} 2>&1\n"));
        assertArrayEquals(new byte[]{0, (byte) 255}, sink.toByteArray());
        assertEquals("/sdcard", session.workingDirectory());
        assertEquals(7, result.exitCode());
        assertTrue(requests.get(1).contains(" >&2\n"));
    }

    @Test public void quotedDirectorySpellingIsRetained() throws Exception {
        final String directory = "/tmp/link/../Dmitry's files";
        final var session = new PersistentAutomationShellSession(directory, (command, stdout) -> {
            assertTrue(command.contains("cd -- " + ShellCommandLine.quote(directory)));
            return result(directory, 0);
        }, "test");
        assertEquals(directory, session.execute("pwd").workingDirectory());
    }

    @Test public void failedOrCancelledShellReappliesLastConfirmedDirectory() throws Exception {
        final int[] execution = {0};
        final boolean[] cancelled = {false};
        final var executor = new PersistentAutomationShellSession.CommandExecutor() {
            @Override public ShellCommandOutput.Result execute(String command, ShellCommandOutput.Sink sink)
                    throws IOException {
                if (execution[0]++ == 1) throw new IOException("closed");
                assertTrue(command.contains("cd -- "));
                return result("/sdcard", 0);
            }
            @Override public void cancelCurrent() { cancelled[0] = true; }
        };
        final var session = new PersistentAutomationShellSession("/tmp", executor, "test");
        session.execute("cd /sdcard");
        assertThrows(IOException.class, () -> session.execute("failed"));
        session.execute("pwd");
        session.cancelCurrentCommand();
        assertTrue(cancelled[0]);
        session.execute("pwd");
    }

    @Test public void invalidDirectoriesAndCommandsNeverReachExecutor() {
        for (String directory : new String[]{null, "", "relative", "/tmp\0other",
                "/tmp\nother", "/tmp\rother", "/" + "x".repeat(4096)}) {
            assertThrows(IllegalArgumentException.class, () -> new PersistentAutomationShellSession(
                    directory, (c, s) -> { throw new AssertionError(); }, "test"));
        }
        final var session = new PersistentAutomationShellSession("/tmp",
                (c, s) -> { throw new AssertionError(); }, "test");
        for (String command : new String[]{null, "", " ", "echo\0bad"}) {
            assertThrows(IllegalArgumentException.class, () -> session.execute(command));
        }
    }

    @Test public void cancellationDoesNotWaitForCommandMonitor() throws Exception {
        final var started = new java.util.concurrent.CountDownLatch(1);
        final var cancelled = new java.util.concurrent.CountDownLatch(1);
        final var executor = new PersistentAutomationShellSession.CommandExecutor() {
            @Override public ShellCommandOutput.Result execute(String c, ShellCommandOutput.Sink s) throws IOException {
                started.countDown();
                try { assertTrue(cancelled.await(2, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException e) { throw new IOException(e); }
                return result("/tmp", 0);
            }
            @Override public void close() { cancelled.countDown(); }
        };
        final var session = new PersistentAutomationShellSession("/tmp", executor, "test");
        final var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            final var job = worker.submit(() -> session.execute("running"));
            assertTrue(started.await(2, java.util.concurrent.TimeUnit.SECONDS));
            session.close();
            job.get(2, java.util.concurrent.TimeUnit.SECONDS);
        } finally { worker.shutdownNow(); }
    }

    private static ShellCommandOutput.Result result(String cwd, int code) {
        return new ShellCommandOutput.Result(code, cwd, "output\n", "error\n", false);
    }
}
