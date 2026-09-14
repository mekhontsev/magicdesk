package io.github.mekhontsev.magicdesk;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Binary pipe framing and per-command boundaries, independent of terminal rendering. */
final class ShellCommandOutput {
    static final int STDOUT = 33;
    static final int STDERR = 34;
    static final int MAX_FRAME = 8192;
    private static final int MAX_COMPLETION = 16 * 1024;

    interface Sink {
        void write(byte[] bytes, int offset, int length) throws IOException;
    }

    record Result(int exitCode, String workingDirectory, String output,
            String stderr, boolean stderrTruncated) { }

    static final class Failure extends IOException {
        final String stderr;
        final boolean stderrTruncated;
        Failure(IOException cause, Capture diagnostics) {
            super(cause.getMessage(), cause);
            stderr = diagnostics.text();
            stderrTruncated = diagnostics.truncated;
        }
    }

    static Result read(InputStream source, String marker, Sink destination) throws IOException {
        final Capture combined = new Capture();
        final Capture diagnostic = new Capture();
        final Channel stdout = new Channel(marker, destination == null ? combined : destination);
        final Channel stderr = new Channel(marker, (bytes, offset, count) -> {
            diagnostic.write(bytes, offset, count);
            if (destination == null) combined.write(bytes, offset, count);
        });
        final DataInputStream input = new DataInputStream(source);
        try {
            while (stdout.completion == null || stderr.completion == null) {
                final int kind = input.readUnsignedByte();
                final int length = input.readInt();
                if ((kind != STDOUT && kind != STDERR) || length < 1 || length > MAX_FRAME) {
                    throw new IOException("invalid shell output frame");
                }
                final byte[] bytes = new byte[length];
                input.readFully(bytes);
                (kind == STDOUT ? stdout : stderr).accept(bytes);
            }
            if (!stdout.completion.equals(stderr.completion)) {
                throw new IOException("shell output boundaries disagree");
            }
            return new Result(stdout.completion.exitCode, stdout.completion.directory,
                    combined.text(), diagnostic.text(), diagnostic.truncated);
        } catch (IOException error) { throw new Failure(error, diagnostic); }
    }

    private record Completion(int exitCode, String directory) { }

    /** Retains only a possible marker, never decodes user stdout as text. */
    private static final class Channel {
        final byte[] delimiter;
        final Sink sink;
        final ByteArrayOutputStream pending = new ByteArrayOutputStream();
        ByteArrayOutputStream line;
        int matched;
        Completion completion;

        Channel(String marker, Sink sink) {
            delimiter = ("\n" + marker).getBytes(StandardCharsets.UTF_8);
            this.sink = sink;
        }

        void accept(byte[] bytes) throws IOException {
            for (byte value : bytes) consume(value & 255);
            flush();
        }

        void consume(int value) throws IOException {
            if (completion != null) throw new IOException("output after command boundary");
            if (line != null) {
                if (value != '\n') {
                    if (line.size() >= MAX_COMPLETION) throw new IOException("shell completion too long");
                    line.write(value);
                    return;
                }
                final byte[] candidate = line.toByteArray();
                line = null;
                final String text = new String(candidate, StandardCharsets.UTF_8);
                final int tab = text.indexOf('\t');
                if (tab > 0 && tab + 1 < text.length()) {
                    try {
                        completion = new Completion(Integer.parseInt(text.substring(0, tab)),
                                DesktopExecWorkingDirectory.normalize(text.substring(tab + 1)));
                        flush();
                        return;
                    } catch (IllegalArgumentException ignored) {
                        // A similar line produced by the command is ordinary output.
                    }
                }
                pending.write(delimiter, 0, delimiter.length);
                pending.write(candidate, 0, candidate.length);
                consume('\n');
                return;
            }
            if (value == (delimiter[matched] & 255)) {
                if (++matched == delimiter.length) {
                    matched = 0;
                    line = new ByteArrayOutputStream();
                }
                return;
            }
            if (matched > 0) {
                pending.write(delimiter, 0, matched);
                matched = 0;
                consume(value);
            } else {
                pending.write(value);
            }
        }

        void flush() throws IOException {
            if (pending.size() == 0) return;
            final byte[] bytes = pending.toByteArray();
            pending.reset();
            sink.write(bytes, 0, bytes.length);
        }
    }

    private static final class Capture implements Sink {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean truncated;

        @Override public void write(byte[] input, int offset, int length) {
            final int accepted = Math.min(length,
                    BoundedProcessRunner.DEFAULT_MAX_OUTPUT_BYTES - bytes.size());
            bytes.write(input, offset, accepted);
            truncated |= accepted < length;
        }

        String text() {
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8)
                    + (truncated ? "\n[MagicDesk: command output truncated]" : "");
        }
    }
}
