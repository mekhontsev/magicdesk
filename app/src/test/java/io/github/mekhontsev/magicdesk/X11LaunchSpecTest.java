package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.Assert.*;

public final class X11LaunchSpecTest {
    @Test public void authorityIsStandardWildcardRecordWithRandomCookie() throws Exception {
        X11LaunchSpec spec = new X11LaunchSpec("/app.apk", "/lib", "org.example.host", "com.example.termux", "/termux/home/.cache/magicdesk/x11", "/termux/tmp", "/termux/xkb", false, 96, false);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(spec.stdin.trim())));
        assertEquals(65535, in.readUnsignedShort());
        assertEquals(0, in.readUnsignedShort());
        assertEquals(0, in.readUnsignedShort());
        assertEquals("MIT-MAGIC-COOKIE-1", new String(in.readNBytes(in.readUnsignedShort()), StandardCharsets.US_ASCII));
        assertEquals(16, in.readUnsignedShort());
        assertEquals(16, in.readNBytes(16).length);
        assertEquals(-1, in.read());
        assertNotEquals(spec.stdin, new X11LaunchSpec("/app.apk", "/lib", "org.example.host", "com.example.termux", "/termux/home/.cache/magicdesk/x11", "/termux/tmp", "/termux/xkb", false, 96, false).stdin);
    }

    @Test public void serverOwnsDisplayAllocationAndDoesNotExposeTcp() {
        X11LaunchSpec spec = new X11LaunchSpec("/path with 'quote/app.apk", "/lib", "org.example.host", "com.example.termux", "/termux/home/.cache/magicdesk/x11", "/termux/tmp", "/termux/xkb", false, 192, true);
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
        X11LaunchSpec a = new X11LaunchSpec("/app.apk", "/lib", "org.example.host", "com.example.termux", "/one/home/x11", "/one/tmp", "/one/xkb", false, 96, false);
        X11LaunchSpec b = new X11LaunchSpec("/app.apk", "/lib", "org.example.host", "com.example.termux", "/two/home/x11", "/two/tmp", "/two/xkb", false, 96, true);
        assertTrue(a.serverCommand.contains("MAGICDESK_X11_XSETTINGS='0'"));
        assertTrue(a.serverCommand.contains("MAGICDESK_X11_HOST_WM='0'"));
        String command = "cd ~/work && firefox --new-instance";
        assertNotEquals(a.id, b.id);
        assertNotEquals(a.token, b.token);
        assertNotEquals(a.authorityFile, b.authorityFile);
        assertEquals("export DISPLAY=':17' XAUTHORITY=" + ShellCommandLine.quote(a.authorityFile)
                + " MAGICDESK_X11_RUNTIME=" + ShellCommandLine.quote(a.directory)
                + " MAGICDESK_X11_TMPDIR=" + ShellCommandLine.quote(a.temporaryDirectory)
                + "\n" + command, a.clientCommand("17", command));
    }

    @Test public void graphicalCommandsDrainOutputAndPreserveFailureStatus() throws Exception {
        String shell = System.getenv().getOrDefault("MAGICDESK_TEST_BASH", "bash");
        Process process = new ProcessBuilder(shell, "-c", CommandExecution.boundedOutput(
                "printf '%20000s' x; printf 'final error' >&2; exit 17")).start();
        byte[] output = process.getErrorStream().readAllBytes();
        assertEquals(new String(output, StandardCharsets.UTF_8), 17, process.waitFor());
        assertEquals(16384, output.length);
        assertTrue(new String(output, StandardCharsets.UTF_8).endsWith("final error"));
        assertEquals(0, process.getInputStream().readAllBytes().length);
    }

    @Test public void rejectsInvalidDisplayAndEmptyCommands() {
        X11LaunchSpec spec = new X11LaunchSpec("/app.apk", "/lib", "org.example.host", "com.example.termux", "/home/x11", "/tmp", "/xkb", false, 96, false);
        for (String display : new String[]{"", ":0", "0;id", "-1", "65536"})
            assertThrows(IllegalArgumentException.class, () -> spec.clientCommand(display, "true"));
        assertThrows(IllegalArgumentException.class, () -> spec.clientCommand("0", " "));
        assertThrows(IllegalArgumentException.class, () -> X11LaunchSpec.authority(new byte[15]));
    }
}
