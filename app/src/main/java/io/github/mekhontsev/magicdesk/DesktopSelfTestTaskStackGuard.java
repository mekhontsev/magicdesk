package io.github.mekhontsev.magicdesk;

import java.util.Arrays;

/** Connects self-test stages to the shell task-stack event guard. */
final class DesktopSelfTestTaskStackGuard {
    private static final int MAX_REPORTED_ANOMALIES = 8;

    private static boolean sRequested;
    private static boolean sActive;
    private static String sUnavailableReason = "";

    private DesktopSelfTestTaskStackGuard() {
    }

    static synchronized void begin(
            final int displayId,
            final int hostTaskId,
            final String stage) {
        if (sRequested) {
            return;
        }
        sRequested = true;
        sActive = MagicDeskRuntime.startSelfTestTaskStackGuard(
                displayId, hostTaskId, stage);
        sUnavailableReason = sActive
                ? "" : "desktop task observer is unavailable";
    }

    static synchronized void stage(final String stage) {
        if (sActive) {
            MagicDeskRuntime.setSelfTestTaskStackGuardStage(stage);
        }
    }

    static synchronized void finish(final DesktopSelfTestResult result) {
        if (!sRequested) {
            return;
        }
        final SelfTestTaskStackReport report = sActive
                ? MagicDeskRuntime.stopSelfTestTaskStackGuard()
                : SelfTestTaskStackReport.unavailable(sUnavailableReason);
        sRequested = false;
        sActive = false;
        sUnavailableReason = "";
        if (result == null) {
            return;
        }
        if (report == null || !report.available) {
            result.add(DesktopSelfTestResult.State.NOT_TESTED,
                    "WINDOW-STACK-001",
                    "Keep the task hierarchy stable during window operations",
                    report == null || report.error.isEmpty()
                            ? "task-stack guard unavailable" : report.error);
            return;
        }
        final String counts = "stages=" + report.stageCount
                + ", samples=" + report.sampleCount
                + ", events=" + report.eventCount
                + ", dropped=" + report.droppedSamples;
        if (report.anomalies.length == 0) {
            result.add(DesktopSelfTestResult.State.PASS,
                    "WINDOW-STACK-001",
                    "Keep the task hierarchy stable during window operations",
                    counts);
            return;
        }
        final int reported = Math.min(
                MAX_REPORTED_ANOMALIES, report.anomalies.length);
        final String detail = counts + "; " + String.join("; ",
                Arrays.copyOf(report.anomalies, reported))
                + (reported < report.anomalies.length
                        ? "; plus " + (report.anomalies.length - reported)
                                + " more"
                        : "");
        result.add(DesktopSelfTestResult.State.FAIL,
                "WINDOW-STACK-001",
                "Keep the task hierarchy stable during window operations",
                detail);
    }

    static synchronized void cancel() {
        if (sActive) {
            MagicDeskRuntime.stopSelfTestTaskStackGuard();
        }
        sRequested = false;
        sActive = false;
        sUnavailableReason = "";
    }
}
