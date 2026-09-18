package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class TermuxSetupCommandTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private Path home, properties;

    @Before public void setup() throws Exception {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"));
        home = Files.createDirectory(temporary.getRoot().toPath().resolve("home with spaces"));
        properties = home.resolve(".termux/termux.properties");
    }

    @Test public void createsMissingConfigurationAndReloadsIt() throws Exception {
        runSetup();
        assertEquals("allow-external-apps = true\n", Files.readString(properties));
        runSetup();
        assertEquals("allow-external-apps = true\n", Files.readString(properties));
    }

    @Test public void preservesOtherSettingsAndCommentsWithoutAccumulatingOverrides() throws Exception {
        Files.createDirectories(properties.getParent());
        Files.writeString(properties, "# Example\n# allow-external-apps = false\n"
                + "extra-keys = [['ESC']]\n allow-external-apps : false\n"
                + "allow-external-apps=false\nallow-external-apps true\nallow-external-apps-other=value\n"
                + "terminal-cursor-style=bar");
        runSetup();
        final String configured = Files.readString(properties);
        assertEquals("# Example\n# allow-external-apps = false\nextra-keys = [['ESC']]\n"
                + "allow-external-apps = true\nallow-external-apps-other=value\nterminal-cursor-style=bar\n", configured);
        runSetup();
        assertEquals(configured, Files.readString(properties));
        try (var files = Files.list(properties.getParent())) { assertEquals(1, files.count()); }
    }

    @Test public void retainsLinkedUserConfiguration() throws Exception {
        Files.createDirectories(properties.getParent());
        Path target = Files.writeString(home.resolve("dotfiles"), "color=unchanged\n");
        Files.createSymbolicLink(properties, target);
        runSetup();
        assertTrue(Files.isSymbolicLink(properties));
        assertEquals("color=unchanged\nallow-external-apps = true\n", Files.readString(target));
    }

    private void runSetup() throws Exception {
        final String shell = System.getenv("PREFIX") == null ? "/bin/bash" : System.getenv("PREFIX") + "/bin/bash";
        final var command = new ProcessBuilder(shell, "--noprofile", "--norc", "-c",
                "termux-reload-settings() { printf reload; }\n" + TermuxSetupCommand.SCRIPT);
        command.environment().put("HOME", home.toString());
        final var result = BoundedProcessRunner.run(command.start(), 5000, 8192);
        assertEquals(result.output, 0, result.exitCode);
        assertEquals("reload", result.output);
    }
}
