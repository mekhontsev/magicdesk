package io.github.mekhontsev.magicdesk;

import java.io.IOException;

/** Reads bounded WindowManager ownership failures emitted during a self-test. */
final class WindowTransitionLogDiagnostics {
    private static final int MAX_LOG_LINES = 512;

    private WindowTransitionLogDiagnostics() {
    }

    static Snapshot capture(
            final long startedAtMillis,
            final int displayId) {
        if (startedAtMillis <= 0L) {
            return Snapshot.unavailable("self-test start time is unavailable");
        }
        final long startSecond = startedAtMillis / 1_000L;
        final String command = "/system/bin/logcat -d -v epoch -T "
                + startSecond + ".000 -m " + MAX_LOG_LINES
                + " -s ShellTransitions:E TransitionChain:E";
        try {
            return parse(
                    ShellAccess.run(command), startedAtMillis, displayId);
        } catch (IOException | RuntimeException error) {
            return Snapshot.unavailable(ShellAccess.usefulMessage(error));
        }
    }

    static Snapshot parse(
            final String logcat,
            final long startedAtMillis,
            final int displayId) {
        if (logcat == null || startedAtMillis <= 0L) {
            throw new IllegalArgumentException(
                    "transition log and start time are required");
        }
        int nonPending = 0;
        int mismatched = 0;
        int emptyChain = 0;
        int includedLines = 0;
        boolean testDisplayReferenced = false;
        boolean magicDeskReferenced = false;
        for (final String sourceLine : logcat.split("\\r?\\n")) {
            final String line = sourceLine.trim();
            final long timestampMillis = timestampMillis(line);
            if (timestampMillis < startedAtMillis) {
                continue;
            }
            includedLines++;
            if (line.contains(
                    "Got transitionReady for non-pending transition")) {
                nonPending++;
            }
            if (line.contains("Mismatch between current collecting")) {
                mismatched++;
            }
            if (line.contains("Can't collect into a chain with no transition")) {
                emptyChain++;
            }
            if (displayId >= 0
                    && (line.contains("Display{#" + displayId + ' ')
                            || line.contains("display=" + displayId))) {
                testDisplayReferenced = true;
            }
            if (line.contains(BuildConfig.APPLICATION_ID)
                    || line.contains("MagicDesk fullscreen")) {
                magicDeskReferenced = true;
            }
        }
        return new Snapshot(
                true,
                "",
                nonPending,
                mismatched,
                emptyChain,
                testDisplayReferenced,
                magicDeskReferenced,
                includedLines >= MAX_LOG_LINES);
    }

    private static long timestampMillis(final String line) {
        final int separator = line.indexOf(' ');
        final String value = separator < 0 ? line : line.substring(0, separator);
        final int decimal = value.indexOf('.');
        if (decimal <= 0) {
            return -1L;
        }
        try {
            final long seconds = Long.parseLong(value.substring(0, decimal));
            String fraction = value.substring(decimal + 1);
            if (fraction.length() > 3) {
                fraction = fraction.substring(0, 3);
            }
            while (fraction.length() < 3) {
                fraction += '0';
            }
            return seconds * 1_000L + Long.parseLong(fraction);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    static final class Snapshot {
        final boolean available;
        final String error;
        final int nonPendingCount;
        final int mismatchCount;
        final int emptyChainCount;
        final boolean testDisplayReferenced;
        final boolean magicDeskReferenced;
        final boolean truncated;

        Snapshot(
                final boolean available,
                final String error,
                final int nonPendingCount,
                final int mismatchCount,
                final int emptyChainCount,
                final boolean testDisplayReferenced,
                final boolean magicDeskReferenced,
                final boolean truncated) {
            this.available = available;
            this.error = error;
            this.nonPendingCount = nonPendingCount;
            this.mismatchCount = mismatchCount;
            this.emptyChainCount = emptyChainCount;
            this.testDisplayReferenced = testDisplayReferenced;
            this.magicDeskReferenced = magicDeskReferenced;
            this.truncated = truncated;
        }

        static Snapshot unavailable(final String error) {
            return new Snapshot(
                    false,
                    error == null ? "transition log is unavailable" : error,
                    0,
                    0,
                    0,
                    false,
                    false,
                    false);
        }

        int errorCount() {
            return nonPendingCount + mismatchCount + emptyChainCount;
        }

        String detail() {
            return "nonPending=" + nonPendingCount
                    + ", mismatched=" + mismatchCount
                    + ", emptyChain=" + emptyChainCount
                    + ", testDisplayReferenced=" + testDisplayReferenced
                    + ", magicDeskReferenced=" + magicDeskReferenced
                    + (truncated ? ", logTruncated=true" : "");
        }
    }
}
