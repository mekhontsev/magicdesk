package io.github.mekhontsev.magicdesk;

final class InputBridgeDiagnostics {
    private static int sAttempts;
    private static int sReadySessions;
    private static int sPointerOnlySessions;
    private static int sFailures;
    private static int sSourceRefreshFailures;
    private static int sBridgeAnomalies;
    private static int sPointerReactivations;
    private static int sLastRoutingDisplayId = -1;
    private static String sLastFailure = "";

    private InputBridgeDiagnostics() {
    }

    static synchronized void noteAttempt(final int routingDisplayId) {
        sAttempts++;
        sLastRoutingDisplayId = routingDisplayId;
    }

    static synchronized void noteReady(final boolean fullKeyboardBridge) {
        if (fullKeyboardBridge) {
            sReadySessions++;
        } else {
            sPointerOnlySessions++;
        }
    }

    static synchronized void noteFailure(final Throwable error) {
        sFailures++;
        sLastFailure = usefulMessage(error);
    }

    static synchronized void noteSourceRefreshFailure(final Throwable error) {
        sSourceRefreshFailures++;
        sLastFailure = usefulMessage(error);
    }

    static synchronized void noteBridgeAnomaly(final String detail) {
        sBridgeAnomalies++;
        sLastFailure = normalize(detail);
    }

    static synchronized void notePointerReactivation() {
        sPointerReactivations++;
    }

    static synchronized Snapshot snapshot() {
        return new Snapshot(
                sAttempts,
                sReadySessions,
                sPointerOnlySessions,
                sFailures,
                sSourceRefreshFailures,
                sBridgeAnomalies,
                sPointerReactivations,
                sLastRoutingDisplayId,
                sLastFailure);
    }

    static synchronized void resetForTests() {
        sAttempts = 0;
        sReadySessions = 0;
        sPointerOnlySessions = 0;
        sFailures = 0;
        sSourceRefreshFailures = 0;
        sBridgeAnomalies = 0;
        sPointerReactivations = 0;
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
        final int pointerOnlySessions;
        final int failures;
        final int sourceRefreshFailures;
        final int bridgeAnomalies;
        final int pointerReactivations;
        final int routingDisplayId;
        final String lastFailure;

        Snapshot(
                final int attempts,
                final int readySessions,
                final int pointerOnlySessions,
                final int failures,
                final int sourceRefreshFailures,
                final int bridgeAnomalies,
                final int pointerReactivations,
                final int routingDisplayId,
                final String lastFailure) {
            this.attempts = attempts;
            this.readySessions = readySessions;
            this.pointerOnlySessions = pointerOnlySessions;
            this.failures = failures;
            this.sourceRefreshFailures = sourceRefreshFailures;
            this.bridgeAnomalies = bridgeAnomalies;
            this.pointerReactivations = pointerReactivations;
            this.routingDisplayId = routingDisplayId;
            this.lastFailure = lastFailure;
        }

        String reportLine() {
            return "attempts=" + attempts
                    + ", ready=" + readySessions
                    + ", pointerOnly=" + pointerOnlySessions
                    + ", failures=" + failures
                    + ", sourceRefreshFailures=" + sourceRefreshFailures
                    + ", anomalies=" + bridgeAnomalies
                    + ", pointerReactivateCommands=" + pointerReactivations
                    + ", routingDisplay=" + routingDisplayId
                    + (lastFailure.isEmpty()
                            ? "" : ", lastFailure=" + lastFailure);
        }
    }
}
