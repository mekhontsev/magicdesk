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
                "DisplayOperations", "ShellVirtualDisplays", "FrameworkActivityLaunchApi",
                "OrdinaryActivityLaunch"}) {
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

    @Test public void independentStartAndTermuxConsoleDoNotStartDesktopOrRequestShell() throws Exception {
        final String start = source("FullscreenStartController");
        assertFalse(start.contains("MagicDeskRuntime.start(activity)"));
        assertTrue(start.contains("MagicDeskRuntime.startTools(activity, false)"));
        assertTrue(source("CommandConsoleActivity").contains(
                "MagicDeskRuntime.startTools(this, mBackend == DesktopExecBackend.SHELL)"));
        assertTrue(RuntimeSourceFixture.methods("SettingsActivity", "render").contains(
                "ShellAccess.isReady() ? MagicDeskSettings.load() : null"));
        final String recents = RuntimeSourceFixture.methods("FullscreenStartController", "loadRunning");
        assertTrue(recents.indexOf("if (!ShellAccess.isReady())") < recents.indexOf("TaskCommandQueue"));
    }

    @Test public void terminalViewDestructionDoesNotCloseSession() throws Exception {
        final String activity = source("CommandConsoleActivity");
        final String destroy = activity.substring(activity.indexOf("protected void onDestroy()"),
                activity.indexOf("public void onShellStateChanged"));
        assertTrue(destroy.contains("ConsoleTerminalRegistry.detach"));
        assertFalse(destroy.contains("mSession.close()"));
    }
}
