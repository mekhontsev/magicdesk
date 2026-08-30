package io.github.mekhontsev.magicdesk;

import org.json.JSONException;
import org.json.JSONObject;

/** Bounded process-local routing diagnostics for semantic window requests. */
final class DesktopWindowTransitionDiagnostics {
    private static long sRequests;
    private static long sAccepted;
    private static long sDeclined;
    private static long sFallbacks;
    private static String sLastOperation = "none";
    private static int sLastDisplayId = -1;
    private static int sLastTaskId = -1;

    private DesktopWindowTransitionDiagnostics() {
    }

    static synchronized void recordSubmission(
            final DesktopWindowTransitionRequest request,
            final boolean accepted) {
        sRequests++;
        if (accepted) {
            sAccepted++;
        } else {
            sDeclined++;
        }
        sLastOperation = request.operation.wireName;
        sLastDisplayId = request.displayId;
        sLastTaskId = request.taskId;
    }

    static synchronized void recordFallback(
            final DesktopWindowTransitionRequest request) {
        sFallbacks++;
        sLastOperation = request.operation.wireName;
        sLastDisplayId = request.displayId;
        sLastTaskId = request.taskId;
    }

    static synchronized void appendReport(final StringBuilder report) {
        report.append("## Window transition gateway\n")
                .append("Requests: ").append(sRequests)
                .append(", accepted=").append(sAccepted)
                .append(", declined=").append(sDeclined)
                .append(", repositoryFallbacks=").append(sFallbacks)
                .append('\n')
                .append("Last: ").append(sLastOperation)
                .append(" display=").append(sLastDisplayId)
                .append(" task=").append(sLastTaskId)
                .append('\n');
        DesktopWindowTransitionProvenance.appendReport(report);
        report.append('\n');
    }

    static synchronized JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("requests", sRequests)
                .put("accepted", sAccepted)
                .put("declined", sDeclined)
                .put("repositoryFallbacks", sFallbacks)
                .put("lastOperation", sLastOperation)
                .put("lastDisplayId", sLastDisplayId)
                .put("lastTaskId", sLastTaskId)
                .put("provenance",
                        DesktopWindowTransitionProvenance.toJson());
    }
}
