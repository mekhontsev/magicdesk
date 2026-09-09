package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public final class ToolRuntimeIsolationTest {
    private static String source(final String name) throws Exception {
        return Files.readString(Path.of("src/main/java/io/github/mekhontsev/magicdesk/" + name + ".java"));
    }

    @Test public void independentServicesDoNotOwnDesktopPolicy() throws Exception {
        for (final String file : new String[]{"ConsoleTerminalSession", "ConsoleTerminalInput",
                "DisplayOperations", "ShellVirtualDisplays", "FrameworkActivityLaunchApi"}) {
            final String source = source(file);
            for (final String desktop : new String[]{"DesktopRuntimeBridge", "DesktopTaskController",
                    "WindowedAppLauncher", "DesktopHomeRoleLease", "FrameworkWindowingApi"}) {
                assertFalse(file + " depends on " + desktop, source.contains(desktop));
            }
        }
    }

    @Test public void displayAccessDoesNotEagerlyInitializeWindowOrganizer() throws Exception {
        final String framework = source("FrameworkRuntime");
        assertFalse(framework.contains("= FrameworkWindowingApi.current()"));
        assertFalse(framework.contains("= FrameworkWindowingCompat.current()"));
    }

    @Test public void terminalViewDestructionDoesNotCloseSession() throws Exception {
        final String activity = source("CommandConsoleActivity");
        final String destroy = activity.substring(activity.indexOf("protected void onDestroy()"),
                activity.indexOf("public void onShellStateChanged"));
        assertTrue(destroy.contains("ConsoleTerminalRegistry.detach"));
        assertFalse(destroy.contains("mSession.close()"));
    }
}
