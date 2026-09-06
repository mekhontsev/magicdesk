package io.github.mekhontsev.magicdesk;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Owns a bounded one-shot process and its output streams, with no interactive stdin. */
public final class BoundedProcessRunner {
    static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;
    static final int DEFAULT_MAX_OUTPUT_BYTES = 384 * 1024;

    private static final long TERMINATION_GRACE_MILLIS = 500L;
    private static final long OUTPUT_JOIN_MILLIS = 2_000L;

    private BoundedProcessRunner() {
    }

    public static Result run(final Process process) throws IOException, InterruptedException {
        return run(process, DEFAULT_TIMEOUT_MILLIS, DEFAULT_MAX_OUTPUT_BYTES);
    }

    public static Result run(
            final Process process,
            final long timeoutMillis,
            final int maxOutputBytes) throws IOException, InterruptedException {
        final BinaryResult result = collect(
                process, timeoutMillis, maxOutputBytes, 0, true);
        final String output = new String(result.stdout, StandardCharsets.UTF_8);
        return new Result(result.exitCode,
                result.stdoutTruncated
                        ? output + "\n[MagicDesk: command output truncated]" : output,
                result.stdoutTruncated);
    }

    /** Keeps binary stdout separate from bounded UTF-8 stderr diagnostics. */
    public static BinaryResult runBinary(
            final Process process,
            final long timeoutMillis,
            final int maxStdoutBytes,
            final int maxStderrBytes) throws IOException, InterruptedException {
        return collect(process, timeoutMillis, maxStdoutBytes, maxStderrBytes, false);
    }

    private static BinaryResult collect(
            final Process process,
            final long timeoutMillis,
            final int maxStdoutBytes,
            final int maxStderrBytes,
            final boolean textDiagnostics) throws IOException, InterruptedException {
        if (process == null) {
            throw new IOException("process is null");
        }
        final long started = System.nanoTime();
        final long budget = TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMillis));
        final OutputCollector stdout =
                new OutputCollector(process.getInputStream(), maxStdoutBytes);
        final OutputCollector stderr =
                new OutputCollector(process.getErrorStream(), maxStderrBytes);
        final Thread outputThread = collectorThread(stdout, "MagicDeskCommandOutput");
        final Thread errorThread = collectorThread(stderr, "MagicDeskCommandError");
        try {
            outputThread.start();
            errorThread.start();
            closeQuietly(process.getOutputStream());
            if (!process.waitFor(remaining(started, budget), TimeUnit.NANOSECONDS)) {
                throw new IOException(
                        "command timed out after " + timeoutMillis + " ms"
                                + diagnostics(stdout, stderr, textDiagnostics));
            }
            // Both collectors share the remaining command budget and the same
            // EOF grace: an inherited pipe must not extend each join separately.
            final long joinStarted = System.nanoTime();
            final long joinBudget = Math.min(remaining(started, budget),
                    TimeUnit.MILLISECONDS.toNanos(OUTPUT_JOIN_MILLIS));
            join(outputThread, joinStarted, joinBudget);
            join(errorThread, joinStarted, joinBudget);
            if (outputThread.isAlive() || errorThread.isAlive()) {
                throw new IOException("command output did not close"
                        + diagnostics(stdout, stderr, textDiagnostics));
            }
            final IOException readError = stdout.error() != null
                    ? stdout.error() : stderr.error();
            if (readError != null) {
                throw new IOException(
                        "cannot read command output"
                                + diagnostics(stdout, stderr, textDiagnostics), readError);
            }
            return new BinaryResult(process.exitValue(), stdout.snapshot(),
                    new String(stderr.snapshot(), StandardCharsets.UTF_8),
                    stdout.truncated(), stderr.truncated());
        } finally {
            if (process.isAlive()) {
                terminate(process);
            }
            closeQuietly(process.getInputStream());
            closeQuietly(process.getErrorStream());
            closeQuietly(process.getOutputStream());
            outputThread.interrupt();
            errorThread.interrupt();
            final long cleanupStarted = System.nanoTime();
            final long cleanupBudget = TimeUnit.MILLISECONDS.toNanos(OUTPUT_JOIN_MILLIS);
            try {
                join(outputThread, cleanupStarted, cleanupBudget);
                join(errorThread, cleanupStarted, cleanupBudget);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Thread collectorThread(final OutputCollector collector, final String name) {
        final Thread thread = new Thread(collector, name);
        thread.setDaemon(true);
        return thread;
    }

    private static long remaining(final long started, final long budget) {
        return Math.max(0L, budget - (System.nanoTime() - started));
    }

    private static void join(final Thread thread, final long started, final long budget)
            throws InterruptedException {
        final long remaining = remaining(started, budget);
        if (remaining > 0) {
            TimeUnit.NANOSECONDS.timedJoin(thread, remaining);
        }
    }

    private static String diagnostics(
            final OutputCollector stdout, final OutputCollector stderr,
            final boolean textDiagnostics) {
        return outputSuffix(new String(
                (textDiagnostics ? stdout : stderr).snapshot(), StandardCharsets.UTF_8));
    }

    private static void terminate(final Process process) {
        process.destroy();
        try {
            if (!process.waitFor(
                    TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void closeQuietly(final Closeable stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // Closing the remaining streams must not mask the command result.
        }
    }

    private static String outputSuffix(final String output) {
        final String trimmed = output.trim();
        return trimmed.isEmpty() ? "" : ": " + trimmed;
    }

    public static final class Result {
        public final int exitCode;
        public final String output;
        public final boolean truncated;

        Result(
                final int exitCode,
                final String output,
                final boolean truncated) {
            this.exitCode = exitCode;
            this.output = output;
            this.truncated = truncated;
        }
    }

    public static final class BinaryResult {
        public final int exitCode;
        public final byte[] stdout;
        public final String stderr;
        public final boolean stdoutTruncated;
        public final boolean stderrTruncated;

        BinaryResult(final int exitCode, final byte[] stdout, final String stderr,
                final boolean stdoutTruncated, final boolean stderrTruncated) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
            this.stdoutTruncated = stdoutTruncated;
            this.stderrTruncated = stderrTruncated;
        }
    }

    private static final class OutputCollector implements Runnable {
        private final InputStream mInput;
        private final int mLimit;
        private final ByteArrayOutputStream mOutput = new ByteArrayOutputStream();
        private volatile IOException mError;
        private volatile boolean mTruncated;

        OutputCollector(final InputStream input, final int limit) {
            mInput = input;
            mLimit = Math.max(0, limit);
        }

        @Override
        public void run() {
            final byte[] buffer = new byte[8192];
            try {
                int count;
                while ((count = mInput.read(buffer)) >= 0) {
                    synchronized (mOutput) {
                        final int remaining = mLimit - mOutput.size();
                        if (remaining > 0) {
                            mOutput.write(buffer, 0, Math.min(remaining, count));
                        }
                        if (count > remaining) {
                            mTruncated = true;
                        }
                    }
                }
            } catch (IOException error) {
                mError = error;
            }
        }

        byte[] snapshot() {
            synchronized (mOutput) {
                return mOutput.toByteArray();
            }
        }

        IOException error() {
            return mError;
        }

        boolean truncated() {
            return mTruncated;
        }
    }
}
