package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class TermuxPtyBootstrapTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test(timeout = 10_000L)
    public void concurrentLaunchDoesNotRemoveAnotherLaunchesTemporaryHelper() throws Exception {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"));
        final String prefix = System.getenv("PREFIX");
        final Path shell = prefix == null ? Path.of("/bin/sh") : Path.of(prefix, "bin", "sh");
        assertTrue(Files.isExecutable(shell));
        final Path directory = Files.createDirectories(temporary.getRoot().toPath().resolve(".local/libexec"));
        final Path target = directory.resolve("magicdesk-pty-current");
        final Path old = Files.createFile(target.resolveSibling("magicdesk-pty-old"));
        final byte[] encoded = Base64.getEncoder().encode(
                ("#!" + shell + "\nexit 0\n").getBytes(StandardCharsets.UTF_8));
        // The first decoder announces that its temporary output is open, then
        // waits for stdin EOF. The second launch runs its real cleanup meanwhile.
        final Process first = start(shell, target,
                "base64() { printf 'decoding\\n' >&2; command base64 \"$@\"; }\n");
        try {
            assertEquals("decoding", new BufferedReader(new InputStreamReader(
                    first.getInputStream(), StandardCharsets.UTF_8)).readLine());
            final Process second = start(shell, target, "");
            try {
                second.getOutputStream().write(encoded);
                final var result = BoundedProcessRunner.run(second, 3_000L, 8192);
                assertEquals(result.output, 0, result.exitCode);
            } finally {
                stop(second);
            }
            first.getOutputStream().write(encoded);
            final var result = BoundedProcessRunner.run(first, 3_000L, 8192);
            assertEquals(result.output, 0, result.exitCode);
            assertFalse(Files.exists(old));
            try (var files = Files.list(target.getParent())) {
                assertEquals(1L, files.count());
            }
        } finally {
            stop(first);
        }
    }

    private static Process start(final Path shell, final Path target, final String setup)
            throws Exception {
        final ProcessBuilder builder = new ProcessBuilder(shell.toString(), "-c", setup + TermuxIntegration.PTY_BOOTSTRAP,
                "magicdesk-test", "1", "token", "24", "80", "/tmp", "", target.getFileName().toString())
                .redirectErrorStream(true);
        builder.environment().put("HOME", target.getParent().getParent().getParent().toString());
        builder.environment().put("PREFIX", shell.getParent().getParent().toString());
        builder.environment().put("SHELL", shell.toString());
        return builder.start();
    }

    private static void stop(final Process process) throws Exception {
        process.destroyForcibly();
        assertTrue("test bootstrap leaked", process.waitFor(2L, TimeUnit.SECONDS));
        process.getOutputStream().close();
        process.getInputStream().close();
        process.getErrorStream().close();
    }
}
