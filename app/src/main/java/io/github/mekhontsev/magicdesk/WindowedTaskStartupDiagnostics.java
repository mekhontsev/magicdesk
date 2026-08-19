package io.github.mekhontsev.magicdesk;

final class WindowedTaskStartupDiagnostics {
    private static int sCorrections;
    private static int sLastTaskId = -1;
    private static String sLastActivity = "";

    private WindowedTaskStartupDiagnostics() {
    }

    static synchronized void noteCorrection(
            final int taskId,
            final String activityName) {
        sCorrections++;
        sLastTaskId = taskId;
        sLastActivity = activityName == null ? "" : activityName;
    }

    static synchronized Snapshot snapshot() {
        return new Snapshot(sCorrections, sLastTaskId, sLastActivity);
    }

    static synchronized void resetForTests() {
        sCorrections = 0;
        sLastTaskId = -1;
        sLastActivity = "";
    }

    static final class Snapshot {
        final int corrections;
        final int lastTaskId;
        final String lastActivity;

        Snapshot(
                final int correctionCount,
                final int correctedTaskId,
                final String correctedActivity) {
            corrections = correctionCount;
            lastTaskId = correctedTaskId;
            lastActivity = correctedActivity;
        }

        String reportLine() {
            return "corrections=" + corrections
                    + ", lastTask=" + lastTaskId
                    + (lastActivity.isEmpty()
                            ? "" : ", lastActivity=" + lastActivity);
        }
    }
}
