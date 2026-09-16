package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.junit.Assert.*;

public final class X11LaunchSpecTest {
    @Test public void authorityIsStandardWildcardRecordWithRandomCookie() throws Exception {
        X11LaunchSpec spec = new X11LaunchSpec("/app.apk", "/lib", "org.example.host", "/termux/home", 96, false);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(spec.stdin.trim())));
        assertEquals(65535, in.readUnsignedShort());
        assertEquals(0, in.readUnsignedShort());
        assertEquals(0, in.readUnsignedShort());
        assertEquals("MIT-MAGIC-COOKIE-1", new String(in.readNBytes(in.readUnsignedShort()), StandardCharsets.US_ASCII));
        assertEquals(16, in.readUnsignedShort());
        assertEquals(16, in.readNBytes(16).length);
        assertEquals(-1, in.read());
        assertNotEquals(spec.stdin, new X11LaunchSpec("/app.apk", "/lib", "org.example.host", "/termux/home", 96, false).stdin);
    }

    @Test public void serverOwnsDisplayAllocationAndDoesNotExposeTcp() {
        X11LaunchSpec spec = new X11LaunchSpec("/path with 'quote/app.apk", "/lib", "org.example.host", "/termux/home", 192, true);
        assertTrue(spec.serverCommand.contains("-displayfd 1 -dpi 192 -noreset -nolisten tcp"));
        assertTrue(spec.serverCommand.contains("MAGICDESK_X11_XSETTINGS=1"));
        assertTrue(spec.serverCommand.contains("CLASSPATH=" + ShellCommandLine.quote("/path with 'quote/app.apk")));
        assertTrue(spec.serverCommand.contains("MAGICDESK_X11_OWNER_REQUIRED=1"));
        assertTrue(spec.serverCommand.contains("MAGICDESK_X11_LIBRARY='/lib/libXlorie.so'"));
        assertTrue(spec.serverCommand.contains("trap "));
        assertTrue(spec.serverCommand.contains("MAGICDESK_X11_CONTENT_DIR=\"$runtime/content\""));
        assertTrue(spec.serverCommand.contains("rm -rf -- \"$runtime/content\""));
        assertFalse(spec.serverCommand.contains(" -ac"));
        assertFalse(spec.serverCommand.contains("su "));
        assertFalse(spec.serverCommand.contains("com.termux.x11/."));
        assertFalse(spec.serverCommand.contains("-screen"));
    }

    @Test public void clientUsesOnlyItsSessionsEnvironmentAndPreservesShellSyntax() {
        X11LaunchSpec a = new X11LaunchSpec("/app.apk", "/lib", "org.example.host", "/one/home", 96, false);
        X11LaunchSpec b = new X11LaunchSpec("/app.apk", "/lib", "org.example.host", "/two/home", 96, true);
        assertTrue(a.serverCommand.contains("MAGICDESK_X11_XSETTINGS=0"));
        String command = "cd ~/work && firefox --new-instance";
        assertNotEquals(a.id, b.id);
        assertNotEquals(a.token, b.token);
        assertNotEquals(a.authorityFile, b.authorityFile);
        assertEquals("export DISPLAY=':17' XAUTHORITY=" + ShellCommandLine.quote(a.authorityFile)
                + "\n" + X11LaunchSpec.boundedOutput(command), a.clientCommand("17", command));
    }

    @Test public void graphicalCommandsDrainOutputAndPreserveFailureStatus() throws Exception {
        Process process = new ProcessBuilder("bash", "-c", X11LaunchSpec.boundedOutput(
                "printf '%20000s' x; printf 'final error' >&2; exit 17")).start();
        byte[] output = process.getErrorStream().readAllBytes();
        assertEquals(17, process.waitFor());
        assertEquals(16384, output.length);
        assertTrue(new String(output, StandardCharsets.UTF_8).endsWith("final error"));
        assertEquals(0, process.getInputStream().readAllBytes().length);
    }

    @Test public void rejectsInvalidDisplayAndEmptyCommands() {
        X11LaunchSpec spec = new X11LaunchSpec("/app.apk", "/lib", "org.example.host", "/home", 96, false);
        for (String display : new String[]{"", ":0", "0;id", "-1", "65536"})
            assertThrows(IllegalArgumentException.class, () -> spec.clientCommand(display, "true"));
        assertThrows(IllegalArgumentException.class, () -> spec.clientCommand("0", " "));
        assertThrows(IllegalArgumentException.class, () -> X11LaunchSpec.authority(new byte[15]));
    }
}
