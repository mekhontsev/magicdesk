package io.github.mekhontsev.magicdesk;

import android.os.SystemClock;

import java.io.IOException;

/** Serializes one-shot statistics requests over an existing relay stream. */
final class NativeInputBridgeStatsClient {
    private static final long RESPONSE_TIMEOUT_MILLIS = 1_000L;

    static final class Result {
        final String detail;
        final String error;

        Result(final String detail, final String error) {
            this.detail = detail == null ? "" : detail;
            this.error = error == null ? "" : error;
        }
    }

    private final String mResponsePrefix;
    private long mRequestSequence;
    private NativeInputBridgeStats mLatestStats;

    NativeInputBridgeStatsClient(final String responsePrefix) {
        mResponsePrefix = responsePrefix;
    }

    synchronized Result request(final ShellStreamHandle stream) {
        if (stream == null) {
            return new Result("", "native relay not ready");
        }
        final long requestId = ++mRequestSequence;
        try {
            stream.writeLine("stats " + requestId);
        } catch (IOException error) {
            return new Result(
                    "", "stats command failed: " + usefulMessage(error));
        }

        final long deadline = SystemClock.uptimeMillis()
                + RESPONSE_TIMEOUT_MILLIS;
        while (mLatestStats == null
                || mLatestStats.requestId != requestId) {
            final long remaining = deadline - SystemClock.uptimeMillis();
            if (remaining <= 0L) {
                return new Result("", "stats response timed out");
            }
            try {
                EventDrivenWaits.await(
                        this,
                        EventDrivenWaits.Reason.INPUT_DIAGNOSTICS,
                        remaining);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return new Result("", "stats request interrupted");
            }
        }
        return new Result(mLatestStats.detail, "");
    }

    synchronized boolean accept(final String line) {
        final NativeInputBridgeStats stats =
                NativeInputBridgeStats.parse(line, mResponsePrefix);
        if (stats == null) {
            return false;
        }
        mLatestStats = stats;
        notifyAll();
        return true;
    }

    private static String usefulMessage(final Throwable error) {
        final String message = error == null ? null : error.getMessage();
        final String value = message == null || message.isEmpty()
                ? error == null ? "unknown" : error.getClass().getSimpleName()
                : message;
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
