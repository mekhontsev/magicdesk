package io.github.mekhontsev.magicdesk;

/** Counts phone tasks normalized after an external-display migration. */
final class PhoneTaskNormalizationDiagnostics {
    private static int sNormalizations;
    private static int sLastTaskId = -1;

    private PhoneTaskNormalizationDiagnostics() {
    }

    static synchronized void noteNormalization(final int taskId) {
        sNormalizations++;
        sLastTaskId = taskId;
    }

    static synchronized Snapshot snapshot() {
        return new Snapshot(sNormalizations, sLastTaskId);
    }

    static synchronized void resetForTests() {
        sNormalizations = 0;
        sLastTaskId = -1;
    }

    static final class Snapshot {
        final int normalizations;
        final int lastTaskId;

        Snapshot(final int count, final int taskId) {
            normalizations = count;
            lastTaskId = taskId;
        }

        String reportLine() {
            return "normalizations=" + normalizations
                    + ", lastTask=" + lastTaskId;
        }
    }
}
