package io.github.mekhontsev.magicdesk;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/** Termux launch environment and private Xauthority, independent of Android window placement. */
final class X11LaunchSpec {
    final String id = "x11-" + UUID.randomUUID();
    final String token;
    final String authorityFile;
    final String serverCommand;
    final String stdin;

    X11LaunchSpec(String apk, String nativeLibraryDirectory, String hostPackage, String executorPackage, String termuxHome,
            int dpi, boolean application) {
        if (dpi < 24 || dpi > 1536) throw new IllegalArgumentException("Invalid X11 DPI");
        byte[] secret = new byte[32], cookie = new byte[16];
        SecureRandom random = new SecureRandom();
        random.nextBytes(secret);
        random.nextBytes(cookie);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        String directory = termuxHome + "/.cache/magicdesk/x11/" + id;
        authorityFile = directory + "/Xauthority";
        stdin = Base64.getEncoder().encodeToString(authority(cookie)) + "\n";
        serverCommand = boundedOutput("set -eu\numask 077\n"
                + "mkdir -p " + q(termuxHome + "/.cache/magicdesk/x11") + "\n"
                + "mkdir " + q(directory) + "\n"
                + "trap 'rm -rf -- \"$runtime/content\"; rm -f -- \"$auth\"; rmdir -- \"$runtime\"' EXIT\n"
                + "runtime=" + q(directory) + "\nauth=" + q(authorityFile) + "\n"
                + "base64 -d > \"$auth\"\n"
                + "env -u LD_PRELOAD -u LD_LIBRARY_PATH CLASSPATH=" + q(apk)
                + " MAGICDESK_X11_LIBRARY=" + q(nativeLibraryDirectory + "/libXlorie.so")
                + " MAGICDESK_X11_PACKAGE=" + q(hostPackage)
                + " MAGICDESK_X11_EXECUTOR=" + q(executorPackage)
                + " MAGICDESK_X11_CONTENT_DIR=\"$runtime/content\""
                + " MAGICDESK_X11_XSETTINGS=" + (application ? "1" : "0")
                + " MAGICDESK_X11_SESSION=" + q(id) + " MAGICDESK_X11_TOKEN=" + q(token)
                + " TMPDIR=\"${PREFIX:?}/tmp\" XKB_CONFIG_ROOT=\"$PREFIX/share/X11/xkb\""
                + " /system/bin/app_process -Xnoimage-dex2oat / --nice-name=" + q(id)
                + " io.github.mekhontsev.magicdesk.x11.X11Server -displayfd 1 -dpi " + dpi + " -noreset -nolisten tcp -auth \"$auth\"\n");
    }

    String clientCommand(String display, String command) {
        if (!display.matches("[0-9]{1,5}") || Integer.parseInt(display) > 65535)
            throw new IllegalArgumentException("Invalid X11 display number");
        if (command == null || command.isBlank()) throw new IllegalArgumentException("X11 command is empty");
        return "export DISPLAY=" + q(":" + display) + " XAUTHORITY=" + q(authorityFile)
                + "\n" + boundedOutput(command);
    }

    // Termux accumulates RUN_COMMAND output until exit, even without a result callback.
    // tail drains continuously without retaining the full stream or closing the application's pipe early.
    static String boundedOutput(String command) {
        return "set -o pipefail\n{\n" + command + "\n} 2>&1 | tail -c 16384 >&2\n";
    }

    static byte[] authority(byte[] cookie) {
        if (cookie.length != 16) throw new IllegalArgumentException("Invalid X11 cookie size");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            // FamilyWild and an empty display number allow Xorg to allocate its own display atomically.
            out.writeShort(65535);
            out.writeShort(0);
            out.writeShort(0);
            byte[] protocol = "MIT-MAGIC-COOKIE-1".getBytes(StandardCharsets.US_ASCII);
            out.writeShort(protocol.length);
            out.write(protocol);
            out.writeShort(cookie.length);
            out.write(cookie);
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new AssertionError(impossible); }
    }

    private static String q(String text) { return ShellCommandLine.quote(text); }
}
