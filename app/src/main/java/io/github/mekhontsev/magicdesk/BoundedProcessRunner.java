package io.github.mekhontsev.magicdesk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

final class BoundedProcessRunner {
    static final long DEFAULT_TIMEOUT_MILLIS = 30_000L;
    static final int DEFAULT_MAX_OUTPUT_BYTES = 384 * 1024;

    private static final long TERMINATION_GRACE_MILLIS = 500L;
    private static final long OUTPUT_JOIN_MILLIS = 2_000L;

    private BoundedProcessRunner() {
    }

    static Result run(final Process process) throws IOException, InterruptedException {
        return run(process, DEFAULT_TIMEOUT_MILLIS, DEFAULT_MAX_OUTPUT_BYTES);
    }

    static Result run(
            final Process process,
            final long timeoutMillis,
            final int maxOutputBytes) throws IOException, InterruptedException {
        if (process == null) {
            throw new IOException("process is null");
        }
        final OutputCollector collector =
                new OutputCollector(process.getInputStream(), maxOutputBytes);
        final Thread outputThread =
                new Thread(collector, "MagicDeskCommandOutput");
        outputThread.setDaemon(true);
        outputThread.start();

        final boolean completed;
        try {
            completed = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException error) {
            terminate(process);
            closeQuietly(process.getInputStream());
            throw error;
        }
        if (!completed) {
            terminate(process);
            closeQuietly(process.getInputStream());
            joinQuietly(outputThread);
            throw new IOException(
                    "command timed out after " + timeoutMillis + " ms"
                            + outputSuffix(collector.snapshot()));
        }

        joinQuietly(outputThread);
        if (outputThread.isAlive()) {
            closeQuietly(process.getInputStream());
            throw new IOException("command output did not close"
                    + outputSuffix(collector.snapshot()));
        }
        if (collector.error() != null) {
            throw new IOException(
                    "cannot read command output"
                            + outputSuffix(collector.snapshot()),
                    collector.error());
        }
        return new Result(
                process.exitValue(),
                collector.snapshot(),
                collector.truncated());
    }

    private static void terminate(final Process process) {
        process.destroy();
        try {
            if (!process.waitFor(
                    TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static void joinQuietly(final Thread thread)
            throws InterruptedException {
        thread.join(OUTPUT_JOIN_MILLIS);
    }

    private static void closeQuietly(final InputStream input) {
        try {
            input.close();
        } catch (IOException ignored) {
            // Process termination is already in progress.
        }
    }

    private static String outputSuffix(final String output) {
        final String trimmed = output.trim();
        return trimmed.isEmpty() ? "" : ": " + trimmed;
    }

    static final class Result {
        final int exitCode;
        final String output;
        final boolean truncated;

        Result(
                final int exitCode,
                final String output,
                final boolean truncated) {
            this.exitCode = exitCode;
            this.output = output;
            this.truncated = truncated;
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

        String snapshot() {
            synchronized (mOutput) {
                final String output = new String(
                        mOutput.toByteArray(), StandardCharsets.UTF_8);
                return mTruncated
                        ? output + "\n[MagicDesk: command output truncated]"
                        : output;
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
