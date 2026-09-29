package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class LinuxGraphicalEnvironmentTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void abstractTransportOnlyOverridesAddressAndRetainsExitAndCleanup() throws Exception {
        run(LinuxGraphicalEnvironment.BusTransport.ABSTRACT, 0, 23);
    }

    @Test public void failedBusDoesNotExecuteApplicationAndStillCleansDirectory() throws Exception {
        run(LinuxGraphicalEnvironment.BusTransport.ABSTRACT, 37, 37);
    }

    @Test public void ordinaryLinuxSessionsKeepTheirStandardTransport() throws Exception {
        run(LinuxGraphicalEnvironment.BusTransport.STANDARD, 0, 23);
    }

    private void run(LinuxGraphicalEnvironment.BusTransport transport, int busExit, int expected) throws Exception {
        assumeFalse(System.getProperty("os.name").startsWith("Windows"));
        String shell = System.getenv("PREFIX") == null ? "/bin/sh" : System.getenv("PREFIX") + "/bin/sh";
        Path root = temporary.getRoot().toPath();
        Path bin = Files.createDirectory(root.resolve("bin"));
        Path runtime = Files.createDirectory(root.resolve("runtime ' space"));
        Path capture = root.resolve("bus-arguments");
        script(bin, "mktemp", shell, "printf '%s\\n' \"$MD_DIRECTORY\"\n");
        script(bin, "dbus-uuidgen", shell, "printf '%s\\n' 0123456789abcdef0123456789abcdef\n");
        script(bin, "dbus-daemon", shell, "printf '%s\\0' \"$@\" > \"$MD_CAPTURE\"\nexit " + busExit + "\n");
        script(bin, "dbus-run-session", shell,
                "if [ \"$1\" != -- ]; then\n"
                + "  daemon=${1#--dbus-daemon=}\n  shift\n"
                + "  \"$MD_SHELL\" \"$daemon\" --session --nofork\nfi\n"
                + "test \"$1\" = --\nshift\nexec \"$@\"\n");
        String command = "printf '%s' \"literal ' $value\"; exit 23";
        String wrapped = LinuxGraphicalEnvironment.wrap(GraphicalProtocol.WAYLAND, transport,
                ShellCommandLine.quote(shell) + " -c " + ShellCommandLine.quote(command));
        ProcessBuilder builder = new ProcessBuilder(shell, "-c", wrapped);
        builder.environment().put("PATH", bin + ":" + System.getenv("PATH"));
        builder.environment().put("MD_DIRECTORY", runtime.toString());
        builder.environment().put("MD_CAPTURE", capture.toString());
        builder.environment().put("MD_SHELL", shell);
        builder.environment().put("value", "unchanged");
        var result = BoundedProcessRunner.run(builder.start(), 5000, 16384);
        assertEquals(result.output, expected, result.exitCode);
        assertEquals(busExit == 0 ? "literal ' unchanged" : "", result.output);
        assertFalse(Files.exists(runtime));
        if (transport == LinuxGraphicalEnvironment.BusTransport.ABSTRACT)
            assertEquals(List.of("--address=unix:abstract=magicdesk-0123456789abcdef0123456789abcdef",
                    "--session", "--nofork"), List.of(Files.readString(capture).split("\0")));
        else assertFalse(Files.exists(capture));
    }

    private static void script(Path bin, String name, String shell, String body) throws Exception {
        Path file = Files.writeString(bin.resolve(name), "#!" + shell + "\nset -eu\n" + body);
        assertTrue(file.toFile().setExecutable(true));
    }
}
