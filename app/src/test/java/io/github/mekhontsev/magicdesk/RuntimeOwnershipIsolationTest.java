package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RuntimeOwnershipIsolationTest {
    private static final Path MAIN_JAVA = Path.of("src", "main", "java");
    private static final Path PACKAGE = MAIN_JAVA.resolve(
            Path.of("io", "github", "mekhontsev", "magicdesk"));

    @Test
    public void consoleCommandsEnterThroughRuntime() throws IOException {
        assertFalse(read("DesktopOperations.java")
                .contains("DesktopRuntimeBridge."));
    }

    @Test
    public void sessionCoordinatorDoesNotReenterItsFacade()
            throws IOException {
        final String source = read(
                "DesktopSessionTransitionCoordinator.java");
        assertFalse(source.contains("DesktopOperations."));
        assertFalse(source.contains("PlatformDrivers.current()"));
    }

    private static String read(final String fileName) throws IOException {
        return Files.readString(
                PACKAGE.resolve(fileName), StandardCharsets.UTF_8);
    }
}
