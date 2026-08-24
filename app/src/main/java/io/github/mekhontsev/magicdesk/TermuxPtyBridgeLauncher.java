package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.util.Base64;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Installs the versioned PTY helper inside Termux and starts one bridge. */
final class TermuxPtyBridgeLauncher {
    private static final String HELPER_NAME = "libmagicdesk_pty_bridge.so";
    private static final int MAX_HELPER_BYTES = 512 * 1024;
    private static final String TERMUX_HELPER_DIRECTORY =
            "/data/data/com.termux/files/home/.local/libexec";

    private TermuxPtyBridgeLauncher() {
    }

    static void launch(
            final Context context,
            final int port,
            final String token,
            final int rows,
            final int columns,
            final String workingDirectory,
            final String startupCommand) throws IOException {
        final File helper = new File(
                context.getApplicationInfo().nativeLibraryDir,
                HELPER_NAME);
        final byte[] bytes = Files.readAllBytes(helper.toPath());
        if (bytes.length < 1 || bytes.length > MAX_HELPER_BYTES) {
            throw new IOException("invalid Termux PTY helper size");
        }
        final String digest = sha256(bytes);
        final String target = TERMUX_HELPER_DIRECTORY
                + "/magicdesk-pty-" + digest;
        final String encoded = Base64.encodeToString(bytes, Base64.NO_WRAP);
        TermuxIntegration.runPtyBridge(
                context,
                port,
                token,
                rows,
                columns,
                workingDirectory,
                startupCommand,
                target,
                encoded);
    }

    private static String sha256(final byte[] bytes) throws IOException {
        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
        final StringBuilder result = new StringBuilder(digest.length * 2);
        for (final byte value : digest) {
            result.append(Character.forDigit((value >> 4) & 0x0F, 16));
            result.append(Character.forDigit(value & 0x0F, 16));
        }
        return result.toString();
    }
}
