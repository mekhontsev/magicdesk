package io.github.mekhontsev.magicdesk;

final class TaskActivityModeDiagnostics {
    private static int sCorrections;
    private static int sLastTaskId = -1;
    private static String sLastActivity = "";
    private static String sLastMode = "";

    private TaskActivityModeDiagnostics() {
    }

    static synchronized void noteCorrection(
            final int taskId,
            final String activityName,
            final String restoredMode) {
        sCorrections++;
        sLastTaskId = taskId;
        sLastActivity = activityName == null ? "" : activityName;
        sLastMode = restoredMode == null ? "" : restoredMode;
    }

    static synchronized Snapshot snapshot() {
        return new Snapshot(
                sCorrections, sLastTaskId, sLastActivity, sLastMode);
    }

    static synchronized void resetForTests() {
        sCorrections = 0;
        sLastTaskId = -1;
        sLastActivity = "";
        sLastMode = "";
    }

    static final class Snapshot {
        final int corrections;
        final int lastTaskId;
        final String lastActivity;
        final String lastMode;

        Snapshot(
                final int correctionCount,
                final int correctedTaskId,
                final String correctedActivity,
                final String correctedMode) {
            corrections = correctionCount;
            lastTaskId = correctedTaskId;
            lastActivity = correctedActivity;
            lastMode = correctedMode;
        }

        String reportLine() {
            return "corrections=" + corrections
                    + ", lastTask=" + lastTaskId
                    + (lastMode.isEmpty()
                            ? "" : ", lastMode=" + lastMode)
                    + (lastActivity.isEmpty()
                            ? "" : ", lastActivity=" + lastActivity);
        }
    }
}
