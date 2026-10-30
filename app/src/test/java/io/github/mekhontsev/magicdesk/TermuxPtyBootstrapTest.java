package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class TermuxPtyBootstrapTest {
    @Rule
    public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void bridgeArgumentsCarryOnlyThePackagedHelperPath() {
        String helper = "/data/app/package/lib/arm64/libmagicdesk_pty_bridge.so";
        String setup = CommandShellEnvironment.termuxSetup("12345:token",
                "/data/app/package/base.apk", "/data/app/package/lib/arm64");
        String[] pty = TermuxIntegration.ptyBridgeArguments(setup, 12345, "a".repeat(64),
                24, 80, "/home/test", "", helper);
        String[] pipe = TermuxIntegration.commandBridgeArguments(12345, "a".repeat(64), helper);
        assertEquals(helper, pty[pty.length - 1]);
        assertEquals(helper, pipe[pipe.length - 1]);
        for (String[] arguments : new String[][]{pty, pipe}) {
            assertTrue("Service arguments must not carry a native binary",
                    Arrays.stream(arguments).mapToInt(String::length).sum() < 8192);
        }
    }

    @Test(timeout = 10_000L)
    public void ptyBootstrapRunsPackagedHelperWithoutReadingStdin() throws Exception {
        Path shell = shell();
        Path helper = helper(shell, 512 * 1024);
        byte[] original = Files.readAllBytes(helper);
        String command = "printf '%s' '$not_expanded; command'";
        String[] arguments = TermuxIntegration.ptyBridgeArguments("", 12345, "token", 24, 80,
                "/home/with spaces", command, helper.toString());
        // Test the production bootstrap without loading the host's login profile.
        arguments[0] = "-c";
        assertEquals(String.join("\n", "--socket", "12345", "token", "24", "80",
                        "/home/with spaces", shell.toString(), shell.toString(), command) + "\n",
                run(shell, arguments));
        assertArrayEquals(original, Files.readAllBytes(helper));
        assertFalse(Files.exists(temporary.getRoot().toPath().resolve(".local/libexec")));
    }

    @Test(timeout = 10_000L)
    public void commandBootstrapRunsPackagedHelperWithoutReadingStdin() throws Exception {
        Path shell = shell();
        Path helper = helper(shell, 0);
        String[] arguments = TermuxIntegration.commandBridgeArguments(12345, "token", helper.toString());
        assertEquals(String.join("\n", "--pipe-socket", "12345", "token", shell.toString()) + "\n",
                run(shell, arguments));
        assertFalse(Files.exists(temporary.getRoot().toPath().resolve(".local/libexec")));
    }

    @Test public void allTermuxHelperLaunchesKeepBinaryBytesOutOfBinder() throws Exception {
        String integration = RuntimeSourceFixture.methods("TermuxIntegration", "runPtyBridge", "runCommandBridge");
        String launcher = Files.readString(Path.of(RuntimeSourceFixture.MAIN + "TermuxPtyBridgeLauncher.java"));
        String output = RuntimeSourceFixture.methods("PtyPeerOutput", "runTermux");
        assertFalse(integration.contains("EXTRA_STDIN"));
        assertFalse(integration.contains(".encoded()"));
        assertFalse(launcher.contains("Base64"));
        assertFalse(launcher.contains("readAllBytes"));
        assertTrue(output.contains("TermuxPtyBridgeLauncher.path(context)"));
        assertTrue(RuntimeSourceFixture.methods("TermuxCommandStream", "connect")
                .contains("TermuxPtyBridgeLauncher.path(context)"));
    }

    private Path helper(Path shell, int padding) throws Exception {
        Path helper = temporary.getRoot().toPath().resolve("packaged helper '[$].so");
        Files.writeString(helper, "#!" + shell + "\nprintf '%s\\n' \"$@\"\nexit 0\n#" + "x".repeat(padding));
        assertTrue(helper.toFile().setExecutable(true, true));
        return helper;
    }

    private static Path shell() {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"));
        String prefix = System.getenv("PREFIX");
        Path shell = prefix == null ? Path.of("/bin/bash") : Path.of(prefix, "bin", "bash");
        assertTrue(Files.isExecutable(shell));
        return shell;
    }

    private String run(Path shell, String[] arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add(shell.toString());
        command.addAll(Arrays.asList(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("HOME", temporary.getRoot().toString());
        builder.environment().put("PREFIX", shell.getParent().getParent().toString());
        builder.environment().put("SHELL", shell.toString());
        Process process = builder.start();
        try {
            // EVENT_WAIT: helper exit while stdin remains open; expiry rejects a bootstrap reading stdin.
            assertTrue("Bootstrap waited for a binary on stdin", process.waitFor(3, TimeUnit.SECONDS));
            var result = BoundedProcessRunner.run(process, 3_000L, 8192);
            assertEquals(result.output, 0, result.exitCode);
            return result.output;
        } finally {
            stop(process);
        }
    }

    private static void stop(final Process process) throws Exception {
        process.destroyForcibly();
        assertTrue("test bootstrap leaked", process.waitFor(2L, TimeUnit.SECONDS));
        process.getOutputStream().close();
        process.getInputStream().close();
        process.getErrorStream().close();
    }
}
