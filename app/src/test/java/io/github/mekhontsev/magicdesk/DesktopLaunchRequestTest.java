package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import java.util.List;

import org.junit.Test;

public final class DesktopLaunchRequestTest {
    private static final AppLaunchTarget TARGET =
            AppLaunchTarget.packageDefault("example.application");

    @Test
    public void intentSuppressesPortableExecFallback() {
        final DesktopLaunchRequest request = DesktopLaunchRequest.from(
                new DesktopApplicationShortcut(
                        "Example",
                        "example.application",
                        "/system/bin/am start example",
                        TARGET,
                        "intent:#Intent;package=example.application;end",
                        DesktopLaunchMode.WINDOWED,
                        false,
                        DesktopExecBackend.SHELL,
                        false));

        assertNotNull(request.androidLaunch);
        assertEquals(AndroidLaunchSpec.Kind.INTENT,
                request.androidLaunch.kind);
        assertNull(request.exec);
    }

    @Test
    public void intentMayResolveItsOwnAndroidTarget() {
        final DesktopLaunchRequest request = DesktopLaunchRequest.from(
                new DesktopApplicationShortcut(
                        "Example",
                        "example.application",
                        "",
                        null,
                        "intent:#Intent;component="
                                + "example.application/.MainActivity;end",
                        DesktopLaunchMode.WINDOWED,
                        false,
                        DesktopExecBackend.SHELL,
                        false));

        assertNotNull(request.androidLaunch);
        assertNull(request.androidLaunch.target);
        assertNull(request.exec);
    }

    @Test
    public void packageAndExecProduceOneCompositeRequest() {
        final DesktopLaunchRequest request = DesktopLaunchRequest.from(
                new DesktopApplicationShortcut(
                        "Companion",
                        "example.application",
                        "server --file %f",
                        TARGET,
                        "",
                        DesktopLaunchMode.WINDOWED,
                        false,
                        DesktopExecBackend.SHELL,
                        false,
                        "/storage/emulated/0/project"),
                DesktopLaunchArguments.files(List.of(
                        "/storage/emulated/0/project/file.txt")),
                "/storage/emulated/0/Desktop/Companion.desktop");

        assertNotNull(request.androidLaunch);
        assertEquals(AndroidLaunchSpec.Kind.DEFAULT,
                request.androidLaunch.kind);
        assertNotNull(request.exec);
        assertEquals(
                "/storage/emulated/0/project",
                request.exec.workingDirectory);
        assertEquals(
                "'server' '--file' "
                        + "'/storage/emulated/0/project/file.txt'",
                request.prepareExec().exec.command);
    }

    @Test
    public void execOnlyRequestNeedsNoAndroidTask() {
        final DesktopLaunchRequest request = DesktopLaunchRequest.from(
                new DesktopApplicationShortcut(
                        "Command",
                        "utilities-terminal",
                        "id",
                        null,
                        "",
                        DesktopLaunchMode.AUTO,
                        false,
                        DesktopExecBackend.SHELL,
                        true));

        assertNull(request.androidLaunch);
        assertNotNull(request.exec);
    }
}
