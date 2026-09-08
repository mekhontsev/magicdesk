package io.github.mekhontsev.magicdesk;

final class InputSessionDiagnostics {
    private static int sAttempts;
    private static int sReadySessions;
    private static int sFailures;
    private static int sSourceRefreshFailures;
    private static int sLastRoutingDisplayId = -1;
    private static String sLastFailure = "";

    private InputSessionDiagnostics() {
    }

    static synchronized void noteAttempt(final int routingDisplayId) {
        sAttempts++;
        sLastRoutingDisplayId = routingDisplayId;
    }

    static synchronized void noteReady() {
        sReadySessions++;
    }

    static synchronized void noteFailure(final Throwable error) {
        sFailures++;
        sLastFailure = usefulMessage(error);
    }

    static synchronized void noteSourceRefreshFailure(final Throwable error) {
        sSourceRefreshFailures++;
        sLastFailure = usefulMessage(error);
    }

    static synchronized Snapshot snapshot() {
        return new Snapshot(
                sAttempts,
                sReadySessions,
                sFailures,
                sSourceRefreshFailures,
                sLastRoutingDisplayId,
                sLastFailure);
    }

    static synchronized void resetForTests() {
        sAttempts = 0;
        sReadySessions = 0;
        sFailures = 0;
        sSourceRefreshFailures = 0;
        sLastRoutingDisplayId = -1;
        sLastFailure = "";
    }

    private static String usefulMessage(final Throwable error) {
        if (error == null) {
            return "unknown";
        }
        final String message = error.getMessage();
        return normalize(message == null || message.isEmpty()
                ? error.getClass().getSimpleName() : message);
    }

    private static String normalize(final String detail) {
        if (detail == null) {
            return "";
        }
        final String normalized = detail.replace('\n', ' ').trim();
        return normalized.length() <= 240
                ? normalized : normalized.substring(0, 240);
    }

    static final class Snapshot {
        final int attempts;
        final int readySessions;
        final int failures;
        final int sourceRefreshFailures;
        final int routingDisplayId;
        final String lastFailure;

        Snapshot(
                final int attempts,
                final int readySessions,
                final int failures,
                final int sourceRefreshFailures,
                final int routingDisplayId,
                final String lastFailure) {
            this.attempts = attempts;
            this.readySessions = readySessions;
            this.failures = failures;
            this.sourceRefreshFailures = sourceRefreshFailures;
            this.routingDisplayId = routingDisplayId;
            this.lastFailure = lastFailure;
        }

        String reportLine() {
            return "attempts=" + attempts
                    + ", ready=" + readySessions
                    + ", failures=" + failures
                    + ", sourceRefreshFailures=" + sourceRefreshFailures
                    + ", routingDisplay=" + routingDisplayId
                    + (lastFailure.isEmpty()
                            ? "" : ", lastFailure=" + lastFailure);
        }
    }
}
