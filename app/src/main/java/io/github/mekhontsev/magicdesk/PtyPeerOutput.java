package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.os.Looper;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Slave-side output. Execution uses the PTY owner's identity, never a root fallback. */
final class PtyPeerOutput {
    static final int MAX_BYTES = 64 * 1024;
    private static final long RESULT_TIMEOUT_MILLIS = 8_000;

    private PtyPeerOutput() { }

    static byte[] payload(String text, String base64) {
        if ((text == null) == (base64 == null)) {
            throw new IllegalArgumentException("provide exactly one of text or dataBase64");
        }
        if (text != null && text.length() > MAX_BYTES
                || base64 != null && base64.length() > 4 * ((MAX_BYTES + 2) / 3)) {
            throw new IllegalArgumentException("output exceeds 65536 bytes");
        }
        final byte[] bytes = text != null ? text.getBytes(StandardCharsets.UTF_8)
                : Base64.getDecoder().decode(base64);
        if (bytes.length == 0 || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("output must contain 1 to 65536 bytes");
        }
        return bytes;
    }

    static String emitCommand(PtyEndpoint endpoint, byte[] bytes) {
        if (bytes.length < 1 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("invalid output size");
        return "printf '%s' " + ShellCommandLine.quote(Base64.getEncoder().encodeToString(bytes))
                + " | base64 -d | \"$target\" --emit-pty " + endpoint.processId()
                + " " + endpoint.startTicks() + " " + ShellCommandLine.quote(endpoint.tty())
                + " " + bytes.length;
    }

    static Receipt emit(Context context, DesktopExecBackend backend, PtyEndpoint endpoint,
            byte[] bytes) throws IOException {
        final String command = emitCommand(endpoint, bytes);
        final String output;
        if (backend == DesktopExecBackend.TERMUX) {
            output = runTermux(context, command).stdout;
        } else {
            final File helper = new File(context.getApplicationInfo().nativeLibraryDir,
                    "libmagicdesk_pty_bridge.so");
            output = ShellAccess.executeCommand("target=" + ShellCommandLine.quote(helper.getPath())
                    + "\n" + command).output;
        }
        return Receipt.parse(output, bytes.length);
    }

    static TermuxIntegration.CommandResult runTermux(Context context, String command) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) throw new IOException("PTY operation cannot block UI");
        final var endpoint = TermuxIntegration.inspect(context);
        endpoint.requireAvailable();
        final var helper = TermuxPtyBridgeLauncher.readHelper(context);
        final String script = "set -eu\ntarget=\"${HOME:?}/.local/libexec/" + helper.target() + "\"\n"
                + TermuxPtyBridgeLauncher.INSTALL_HELPER + command;
        final CompletableFuture<TermuxIntegration.CommandResult> completed = new CompletableFuture<>();
        TermuxIntegration.runBackgroundShellCommandForResult(context, endpoint, script,
                "MagicDesk PTY output", endpoint.homeDirectory, RESULT_TIMEOUT_MILLIS,
                helper.encoded(), (result, error) -> {
                    if (error != null) completed.completeExceptionally(error); else completed.complete(result);
                });
        // This waits for Termux's result callback, not for rendering. A timeout
        // cannot retract output already written, so callers must not auto-retry.
        try {
            final var result = completed.get(RESULT_TIMEOUT_MILLIS + 1_000, TimeUnit.MILLISECONDS);
            if (result == null) throw new IOException("missing PTY operation result");
            return result;
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException("PTY operation interrupted", error);
        } catch (ExecutionException | TimeoutException error) {
            throw new IOException("PTY operation completion unconfirmed", error);
        }
    }

    record Receipt(int bytesWritten, int errno) {
        static Receipt parse(String output, int requested) throws IOException {
            final String[] fields = output.trim().split(" ", -1);
            try {
                if (fields.length != 3 || !fields[0].equals("MAGICDESK_EMIT")) {
                    throw new IllegalArgumentException("missing output receipt");
                }
                final int written = Integer.parseInt(fields[1]);
                final int error = Integer.parseInt(fields[2]);
                if (written < 0 || written > requested || error < 0 || (error == 0 && written != requested)) {
                    throw new IllegalArgumentException("invalid output receipt");
                }
                return new Receipt(written, error);
            } catch (IllegalArgumentException error) {
                throw new IOException("PTY output completion unconfirmed", error);
            }
        }
    }
}
