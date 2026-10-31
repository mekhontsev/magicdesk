package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.Assert.*;

public final class X11LaunchSpecTest {
    @Test public void rendererPolicyAlsoControlsServerSideVulkanCopies() {
        for (var mode : io.github.mekhontsev.magicdesk.hosted.HostedRenderer.values()) {
            var spec = new X11LaunchSpec("/app.apk", "/lib", "host", "executor", "/runtime", "/tmp", "/xkb",
                    false, 96, false, "", "/bin/sh", 0, mode);
            assertSame(mode, spec.renderer);
            assertEquals(mode.softwareOnly() ? "1" : "0", spec.environment.get("MAGICDESK_X11_DISABLE_VULKAN_COPY"));
            assertTrue(spec.serverCommand.contains("MAGICDESK_X11_DISABLE_VULKAN_COPY='" + (mode.softwareOnly() ? "1" : "0") + "'"));
            assertFalse(spec.clientCommand("0", "true").contains("DISABLE_VULKAN_COPY"));
        }
    }
    @Test public void guestFileBridgeIsExplicitAndSeparateFromBinderAuthorization() {
        var spec = new X11LaunchSpec("/app.apk", "/lib", "org.example.host", "com.termux", "/runtime", "/tmp", "/xkb",
                false, 96, false, "proot:ubuntu:alice", "/bin/sh", 2, io.github.mekhontsev.magicdesk.hosted.HostedRenderer.AUTOMATIC);
        assertEquals("1", spec.environment.get("MAGICDESK_X11_SHARED_FILES"));
        assertEquals(64, spec.environment.get("MAGICDESK_GUEST_FILES_TOKEN").length());
        assertNotEquals(spec.token, spec.environment.get("MAGICDESK_GUEST_FILES_TOKEN"));
        assertTrue(spec.clientCommand("0", "entry").contains("MAGICDESK_GUEST_FILES_HELPER='/lib/libmagicdesk_guest_files.so'"));
        assertEquals("proot:ubuntu:alice", spec.fileEnvironment);
    }
    @Test public void authorityIsStandardWildcardRecordWithRandomCookie() throws Exception {
        X11LaunchSpec spec = new X11LaunchSpec("/app.apk", "/lib", "org.example.host", "com.example.termux", "/termux/home/.cache/magicdesk/x11", "/termux/tmp", "/termux/xkb", false, 96, false, "", "/bin/sh", 2, io.github.mekhontsev.magicdesk.hosted.HostedRenderer.AUTOMATIC);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(spec.stdin.trim())));
        assertEquals(65535, in.readUnsignedShort());
        assertEquals(0, in.readUnsignedShort());
        assertEquals(0, in.readUnsignedShort());
        assertEquals("MIT-MAGIC-COOKIE-1", new String(in.readNBytes(in.readUnsignedShort()), StandardCharsets.US_ASCII));
        assertEquals(16, in.readUnsignedShort());
        assertEquals(16, in.readNBytes(16).length);
        assertEquals(-1, in.read());
        assertNotEquals(spec.stdin, new X11LaunchSpec("/app.apk", "/lib", "org.example.host", "com.example.termux", "/termux/home/.cache/magicdesk/x11", "/termux/tmp", "/termux/xkb", false, 96, false, "", "/bin/sh", 2, io.github.mekhontsev.magicdesk.hosted.HostedRenderer.AUTOMATIC).stdin);
    }

    @Test public void serverOwnsDisplayAllocationAndDoesNotExposeTcp() {
        X11LaunchSpec spec = new X11LaunchSpec("/path with 'quote/app.apk", "/lib", "org.example.host", "com.example.termux", "/termux/home/.cache/magicdesk/x11", "/termux/tmp", "/termux/xkb", false, 192, true, "", "/bin/sh", 2, io.github.mekhontsev.magicdesk.hosted.HostedRenderer.AUTOMATIC);
        assertTrue(spec.arguments.contains("-displayfd") && spec.arguments.contains("tcp"));
        assertTrue(spec.serverCommand.contains("MAGICDESK_X11_XSETTINGS='1'"));
        assertTrue(spec.serverCommand.contains("MAGICDESK_X11_HOST_WM='1'"));
        assertTrue(spec.serverCommand.contains("CLASSPATH=" + ShellCommandLine.quote("/path with 'quote/app.apk")));
        assertTrue(spec.serverCommand.contains("MAGICDESK_X11_EXECUTOR='com.example.termux'"));
        assertTrue(spec.serverCommand.contains("MAGICDESK_X11_LIBRARY='/lib/libXlorie.so'"));
        assertTrue(spec.serverCommand.contains("trap "));
        assertEquals(spec.directory + "/content", spec.environment.get("MAGICDESK_X11_CONTENT_DIR"));
        assertTrue(spec.serverCommand.contains("rm -rf -- \"$runtime/content\""));
        assertFalse(spec.serverCommand.contains(" -ac"));
        assertFalse(spec.serverCommand.contains("su "));
        assertFalse(spec.serverCommand.contains("com.termux.x11"));
        assertTrue(spec.serverCommand.contains("io.github.mekhontsev.magicdesk.x11.X11Server"));
        assertFalse(spec.serverCommand.contains("-screen"));
    }

    @Test public void clientUsesOnlyItsSessionsEnvironmentAndPreservesShellSyntax() {
        X11LaunchSpec a = new X11LaunchSpec("/app.apk", "/lib", "org.example.host", "com.example.termux", "/one/home/x11", "/one/tmp", "/one/xkb", false, 96, false, "", "/bin/sh", 2, io.github.mekhontsev.magicdesk.hosted.HostedRenderer.AUTOMATIC);
        X11LaunchSpec b = new X11LaunchSpec("/app.apk", "/lib", "org.example.host", "com.example.termux", "/two/home/x11", "/two/tmp", "/two/xkb", false, 96, true, "", "/bin/sh", 2, io.github.mekhontsev.magicdesk.hosted.HostedRenderer.AUTOMATIC);
        assertTrue(a.serverCommand.contains("MAGICDESK_X11_XSETTINGS='0'"));
        assertTrue(a.serverCommand.contains("MAGICDESK_X11_HOST_WM='0'"));
        String command = "cd ~/work && firefox --new-instance";
        assertNotEquals(a.id, b.id);
        assertNotEquals(a.token, b.token);
        assertNotEquals(a.authorityFile, b.authorityFile);
        assertEquals("export DISPLAY=':17' XAUTHORITY=" + ShellCommandLine.quote(a.authorityFile)
                + " MAGICDESK_X11_RUNTIME=" + ShellCommandLine.quote(a.directory)
                + " MAGICDESK_X11_TMPDIR=" + ShellCommandLine.quote(a.temporaryDirectory)
                + "\n" + LinuxAutomationEnvironment.command(command, "/bin/sh"), a.clientCommand("17", command));
    }

    @Test public void commandChildPreservesStdinAndFailureStatus() throws Exception {
        var result = PosixShellFixture.run(CommandExecution.invocation(PosixShellFixture.executable(),
                "cat; printf 'final error' >&2; exit 17", "a'\n$HOME\n"));
        assertEquals(result.stderr, 17, result.exitCode);
        assertEquals("final error", result.stderr);
        assertEquals("a'\n$HOME\n", new String(result.stdout, StandardCharsets.UTF_8));
    }

    @Test public void rejectsInvalidDisplayAndEmptyCommands() {
        X11LaunchSpec spec = new X11LaunchSpec("/app.apk", "/lib", "org.example.host", "com.example.termux", "/home/x11", "/tmp", "/xkb", false, 96, false, "", "/bin/sh", 2, io.github.mekhontsev.magicdesk.hosted.HostedRenderer.AUTOMATIC);
        for (String display : new String[]{"", ":0", "0;id", "-1", "65536"})
            assertThrows(IllegalArgumentException.class, () -> spec.clientCommand(display, "true"));
        assertThrows(IllegalArgumentException.class, () -> spec.clientCommand("0", " "));
        assertThrows(IllegalArgumentException.class, () -> X11LaunchSpec.authority(new byte[15]));
    }
}
