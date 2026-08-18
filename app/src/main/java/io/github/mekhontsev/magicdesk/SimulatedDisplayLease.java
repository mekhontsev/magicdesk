package io.github.mekhontsev.magicdesk;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Owns a temporary overlay display setting and restores it when its Binder dies. */
final class SimulatedDisplayLease implements Closeable {
    static final String SETTING = "overlay_display_devices";
    static final String SPEC = "1920x1080/160";

    private static final String READY = "MAGICDESK_SIMULATED_DISPLAY_READY";
    private static final String RESTORED =
            "MAGICDESK_SIMULATED_DISPLAY_RESTORED";

    private final ShellStreamHandle mStream;
    private final BufferedReader mReader;
    private boolean mClosed;

    private SimulatedDisplayLease(
            final ShellStreamHandle stream,
            final BufferedReader reader) {
        mStream = stream;
        mReader = reader;
    }

    static SimulatedDisplayLease open() throws IOException {
        final ShellStreamHandle stream = ShellAccess.openOwnedStream(
                createCommand());
        final BufferedReader reader = new BufferedReader(
                new InputStreamReader(
                        stream.inputStream(), StandardCharsets.UTF_8));
        try {
            final String line = reader.readLine();
            if (!READY.equals(line)) {
                throw new IOException("simulated display lease failed: " + line);
            }
            return new SimulatedDisplayLease(stream, reader);
        } catch (IOException error) {
            closeQuietly(reader);
            closeQuietly(stream);
            throw error;
        }
    }

    static String createCommand() {
        return "previous=$(/system/bin/settings get global "
                + SETTING + "); "
                + "restored=0; "
                + "restore_overlay() { "
                + "[ \"$restored\" = 1 ] && return; restored=1; "
                + "if [ -z \"$previous\" ] || [ \"$previous\" = null ]; then "
                + "/system/bin/settings delete global "
                + SETTING + " >/dev/null; "
                + "else /system/bin/settings put global "
                + SETTING + " \"$previous\" >/dev/null; fi; "
                + "echo " + RESTORED + "; }; "
                + "trap restore_overlay EXIT; "
                + "trap 'restore_overlay; exit 0' HUP INT TERM; "
                + "/system/bin/settings put global "
                + SETTING + " '" + SPEC + "' >/dev/null || exit 1; "
                + "echo " + READY + "; "
                + "while IFS= read -r line; do "
                + "[ \"$line\" = stop ] && exit 0; done";
    }

    @Override
    public void close() throws IOException {
        if (mClosed) {
            return;
        }
        mClosed = true;
        IOException failure = null;
        boolean restored = false;
        try {
            mStream.writeLine("stop");
            String line;
            while ((line = mReader.readLine()) != null) {
                if (RESTORED.equals(line)) {
                    restored = true;
                }
            }
        } catch (IOException error) {
            failure = error;
        } finally {
            closeQuietly(mReader);
            closeQuietly(mStream);
        }
        if (!restored) {
            throw failure == null
                    ? new IOException(
                            "simulated display lease ended without restoration")
                    : failure;
        }
    }

    private static void closeQuietly(final Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // A later state check reports any persistent overlay setting.
        }
    }
}
