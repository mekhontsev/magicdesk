package io.github.mekhontsev.magicdesk;

final class PhoneTaskGuardDiagnostics {
    private static int sNormalizations;
    private static int sLastTaskId = -1;
    private static int sLauncherStarts;
    private static int sLauncherStartsBlocked;
    private static int sLauncherCrashes;
    private static int sLauncherAnrs;
    private static int sLauncherRecoveries;
    private static int sLauncherProtections;
    private static boolean sAwaitingLauncherRecovery;

    private PhoneTaskGuardDiagnostics() {
    }

    static synchronized void noteNormalization(final int taskId) {
        sNormalizations++;
        sLastTaskId = taskId;
    }

    static synchronized void noteLauncherEvent(
            final int type,
            final boolean protectionActivated) {
        if (type == PhoneLauncherEvent.HOME_START_ALLOWED) {
            sLauncherStarts++;
            if (sAwaitingLauncherRecovery) {
                sLauncherRecoveries++;
                sAwaitingLauncherRecovery = false;
            }
        } else if (type == PhoneLauncherEvent.HOME_START_BLOCKED) {
            sLauncherStartsBlocked++;
        } else if (type == PhoneLauncherEvent.CRASH) {
            sLauncherCrashes++;
            sAwaitingLauncherRecovery = true;
        } else if (type == PhoneLauncherEvent.ANR) {
            sLauncherAnrs++;
        }
        if (protectionActivated) {
            sLauncherProtections++;
        }
    }

    static synchronized Snapshot snapshot() {
        return new Snapshot(
                sNormalizations,
                sLastTaskId,
                sLauncherStarts,
                sLauncherStartsBlocked,
                sLauncherCrashes,
                sLauncherAnrs,
                sLauncherRecoveries,
                sLauncherProtections);
    }

    static synchronized void resetForTests() {
        sNormalizations = 0;
        sLastTaskId = -1;
        sLauncherStarts = 0;
        sLauncherStartsBlocked = 0;
        sLauncherCrashes = 0;
        sLauncherAnrs = 0;
        sLauncherRecoveries = 0;
        sLauncherProtections = 0;
        sAwaitingLauncherRecovery = false;
    }

    static final class Snapshot {
        final int normalizations;
        final int lastTaskId;
        final int launcherStarts;
        final int launcherStartsBlocked;
        final int launcherCrashes;
        final int launcherAnrs;
        final int launcherRecoveries;
        final int launcherProtections;

        Snapshot(
                final int normalizations,
                final int lastTaskId,
                final int launcherStarts,
                final int launcherStartsBlocked,
                final int launcherCrashes,
                final int launcherAnrs,
                final int launcherRecoveries,
                final int launcherProtections) {
            this.normalizations = normalizations;
            this.lastTaskId = lastTaskId;
            this.launcherStarts = launcherStarts;
            this.launcherStartsBlocked = launcherStartsBlocked;
            this.launcherCrashes = launcherCrashes;
            this.launcherAnrs = launcherAnrs;
            this.launcherRecoveries = launcherRecoveries;
            this.launcherProtections = launcherProtections;
        }

        String reportLine() {
            return "normalizations=" + normalizations
                    + ", lastTask=" + lastTaskId
                    + ", launcherStarts=" + launcherStarts
                    + ", launcherStartsBlocked=" + launcherStartsBlocked
                    + ", launcherCrashes=" + launcherCrashes
                    + ", launcherAnrs=" + launcherAnrs
                    + ", launcherRecoveries=" + launcherRecoveries
                    + ", launcherProtections=" + launcherProtections;
        }
    }
}
