package io.github.mekhontsev.magicdesk;

final class PhoneTaskGuardDiagnostics {
    private static int sNormalizations;
    private static int sLastTaskId = -1;

    private PhoneTaskGuardDiagnostics() {
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

        Snapshot(final int normalizations, final int lastTaskId) {
            this.normalizations = normalizations;
            this.lastTaskId = lastTaskId;
        }

        String reportLine() {
            return "normalizations=" + normalizations
                    + ", lastTask=" + lastTaskId;
        }
    }
}
