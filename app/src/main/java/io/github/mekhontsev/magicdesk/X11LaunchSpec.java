package io.github.mekhontsev.magicdesk;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/** Private X server environment; execution and Android placement have separate owners. */
final class X11LaunchSpec {
    final String id = "x11-" + UUID.randomUUID();
    final String token;
    final String authorityFile;
    final String serverCommand;
    final String stdin;
    final String directory;
    final String temporaryDirectory;
    final String keyboardDirectory;
    final String fileEnvironment;
    private final String guestHelper, guestSocket, guestToken;
    final java.util.Map<String, String> environment;
    final java.util.List<String> arguments;

    X11LaunchSpec(String apk, String nativeLibraryDirectory, String hostPackage, String executorPackage,
            String runtimeParent, String temporaryDirectory, String keyboardDirectory, boolean sharedFiles,
            int dpi, boolean application, String fileEnvironment) {
        if (dpi < 24 || dpi > 1536) throw new IllegalArgumentException("Invalid X11 DPI");
        byte[] secret = new byte[32], cookie = new byte[16];
        SecureRandom random = new SecureRandom();
        random.nextBytes(secret);
        random.nextBytes(cookie);
        this.fileEnvironment = fileEnvironment;
        guestHelper = nativeLibraryDirectory + "/libmagicdesk_guest_files.so";
        guestSocket = "magicdesk-files-" + UUID.randomUUID();
        byte[] guestSecret = new byte[32];
        random.nextBytes(guestSecret);
        guestToken = java.util.HexFormat.of().formatHex(guestSecret);
        token = Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
        directory = runtimeParent + "/" + id;
        this.temporaryDirectory = temporaryDirectory.isEmpty() ? directory : temporaryDirectory;
        this.keyboardDirectory = keyboardDirectory;
        authorityFile = directory + "/Xauthority";
        stdin = Base64.getEncoder().encodeToString(authority(cookie)) + "\n";
        java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
        env.put("CLASSPATH", apk);
        env.put("MAGICDESK_X11_LIBRARY", nativeLibraryDirectory + "/libXlorie.so");
        env.put("MAGICDESK_X11_PACKAGE", hostPackage);
        env.put("MAGICDESK_X11_EXECUTOR", executorPackage);
        env.put("MAGICDESK_X11_CONTENT_DIR", directory + "/content");
        env.put("MAGICDESK_X11_SHARED_FILES", sharedFiles || !fileEnvironment.isEmpty() ? "1" : "0");
        if (!fileEnvironment.isEmpty()) {
            env.put("MAGICDESK_GUEST_FILES_SOCKET", guestSocket);
            env.put("MAGICDESK_GUEST_FILES_TOKEN", guestToken);
        }
        env.put("MAGICDESK_X11_XSETTINGS", application ? "1" : "0");
        env.put("MAGICDESK_X11_HOST_WM", application ? "1" : "0");
        env.put("MAGICDESK_X11_SESSION", id);
        env.put("MAGICDESK_X11_TOKEN", token);
        env.put("TMPDIR", this.temporaryDirectory);
        env.put("XKB_CONFIG_ROOT", keyboardDirectory);
        environment = java.util.Collections.unmodifiableMap(env);
        arguments = java.util.List.of("/system/bin/app_process", "-Xnoimage-dex2oat", "/", "--nice-name=" + id,
                "io.github.mekhontsev.magicdesk.x11.X11Server", "-displayfd", "1", "-dpi", Integer.toString(dpi),
                "-noreset", "-nolisten", "tcp", "-auth", authorityFile);
        StringBuilder invocation = new StringBuilder("env -u LD_PRELOAD -u LD_LIBRARY_PATH");
        environment.forEach((key, value) -> invocation.append(' ').append(key).append('=').append(q(value)));
        arguments.forEach(value -> invocation.append(' ').append(q(value)));
        serverCommand = "set -eu\numask 077\n"
                + "mkdir -p " + q(runtimeParent) + "\n"
                + "mkdir " + q(directory) + "\n"
                + "trap 'rm -rf -- \"$runtime/content\"; rm -f -- \"$auth\"; rmdir -- \"$runtime\"' EXIT\n"
                + "runtime=" + q(directory) + "\nauth=" + q(authorityFile) + "\n"
                + "base64 -d > \"$auth\"\n"
                + (!fileEnvironment.isEmpty() ? "chmod 755 \"$runtime\"; chmod 444 \"$auth\"\n" : "")
                + invocation + "\n";
    }

    String clientCommand(String display, String command) {
        if (!display.matches("[0-9]{1,5}") || Integer.parseInt(display) > 65535)
            throw new IllegalArgumentException("Invalid X11 display number");
        if (command == null || command.isBlank()) throw new IllegalArgumentException("X11 command is empty");
        return "export DISPLAY=" + q(":" + display) + " XAUTHORITY=" + q(authorityFile)
                + " MAGICDESK_X11_RUNTIME=" + q(directory)
                + " MAGICDESK_X11_TMPDIR=" + q(temporaryDirectory)
                + (fileEnvironment.isEmpty() ? "" : " MAGICDESK_GUEST_FILES_HELPER=" + q(guestHelper)
                        + " MAGICDESK_GUEST_FILES_SOCKET=" + q(guestSocket) + " MAGICDESK_GUEST_FILES_TOKEN=" + q(guestToken))
                + "\n" + command;
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
