package io.github.mekhontsev.magicdesk;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/** Correlates existing transition callbacks without adding background work. */
final class DesktopWindowTransitionProvenance {
    enum Source {
        MAGICDESK_COMMAND("magicdesk-command"),
        ACTIVITY_HANDOFF_GUARD("activity-handoff-guard"),
        APPLICATION_REQUEST("application-request"),
        FRAMEWORK_EXTERNAL("framework-external"),
        UNKNOWN("unknown");

        final String wireName;

        Source(final String name) {
            wireName = name;
        }
    }

    private static final int MAX_PENDING_TASKS = 128;
    private static final long MAX_CAUSE_AGE_MILLIS = 15_000L;
    private static final LinkedHashMap<Integer, Cause> PENDING =
            new LinkedHashMap<>();
    private static final long[] COUNTS = new long[Source.values().length];

    private static Source sLastSource = Source.UNKNOWN;
    private static String sLastDetail = "none";
    private static int sLastTaskId = -1;

    private DesktopWindowTransitionProvenance() {
    }

    static synchronized void noteMagicDeskCommand(
            final DesktopWindowTransitionRequest request) {
        final String expectedMode = expectedMode(request.operation);
        if (expectedMode == null) {
            return;
        }
        note(
                request.taskId,
                Source.MAGICDESK_COMMAND,
                expectedMode,
                request.operation.wireName + "/" + request.origin);
    }

    static synchronized void noteActivityHandoff(
            final int taskId,
            final String restoredMode,
            final String activityName) {
        note(
                taskId,
                Source.ACTIVITY_HANDOFF_GUARD,
                normalizedMode(restoredMode),
                "restore/" + clean(activityName));
    }

    static synchronized void noteApplicationRequest(
            final int taskId,
            final boolean requestingFullscreen) {
        note(
                taskId,
                Source.APPLICATION_REQUEST,
                requestingFullscreen ? "fullscreen" : "freeform",
                requestingFullscreen ? "immersive-enter" : "immersive-exit");
    }

    static synchronized Observation classify(
            final int taskId,
            final String previousMode,
            final String currentMode) {
        final long now = System.currentTimeMillis();
        final Cause cause = PENDING.remove(Integer.valueOf(taskId));
        final Source source;
        final String detail;
        final long ageMillis;
        if (cause != null
                && now - cause.timestampMillis <= MAX_CAUSE_AGE_MILLIS
                && currentMode.equals(cause.expectedMode)) {
            source = cause.source;
            detail = cause.detail;
            ageMillis = Math.max(0L, now - cause.timestampMillis);
        } else if (isKnownMode(previousMode) && isKnownMode(currentMode)) {
            source = Source.FRAMEWORK_EXTERNAL;
            detail = cause == null
                    ? "no-matching-magicdesk-cause"
                    : "unmatched-cause/" + cause.detail;
            ageMillis = cause == null
                    ? -1L : Math.max(0L, now - cause.timestampMillis);
        } else {
            source = Source.UNKNOWN;
            detail = "unsupported-mode-change/"
                    + clean(previousMode) + "-to-" + clean(currentMode);
            ageMillis = -1L;
        }
        COUNTS[source.ordinal()]++;
        sLastSource = source;
        sLastDetail = detail;
        sLastTaskId = taskId;
        return new Observation(source, detail, ageMillis);
    }

    static synchronized void appendReport(final StringBuilder report) {
        report.append("Transition provenance: magicdesk=")
                .append(COUNTS[Source.MAGICDESK_COMMAND.ordinal()])
                .append(", activityHandoff=")
                .append(COUNTS[Source.ACTIVITY_HANDOFF_GUARD.ordinal()])
                .append(", application=")
                .append(COUNTS[Source.APPLICATION_REQUEST.ordinal()])
                .append(", frameworkExternal=")
                .append(COUNTS[Source.FRAMEWORK_EXTERNAL.ordinal()])
                .append(", unknown=")
                .append(COUNTS[Source.UNKNOWN.ordinal()])
                .append('\n')
                .append("Last observed provenance: source=")
                .append(sLastSource.wireName)
                .append(" task=").append(sLastTaskId)
                .append(" detail=").append(sLastDetail)
                .append('\n');
    }

    static synchronized JSONObject toJson() throws JSONException {
        return new JSONObject()
                .put("magicdeskCommand",
                        COUNTS[Source.MAGICDESK_COMMAND.ordinal()])
                .put("activityHandoffGuard",
                        COUNTS[Source.ACTIVITY_HANDOFF_GUARD.ordinal()])
                .put("applicationRequest",
                        COUNTS[Source.APPLICATION_REQUEST.ordinal()])
                .put("frameworkExternal",
                        COUNTS[Source.FRAMEWORK_EXTERNAL.ordinal()])
                .put("unknown", COUNTS[Source.UNKNOWN.ordinal()])
                .put("lastSource", sLastSource.wireName)
                .put("lastTaskId", sLastTaskId)
                .put("lastDetail", sLastDetail);
    }

    static synchronized void resetForTests() {
        PENDING.clear();
        for (int index = 0; index < COUNTS.length; index++) {
            COUNTS[index] = 0L;
        }
        sLastSource = Source.UNKNOWN;
        sLastDetail = "none";
        sLastTaskId = -1;
    }

    private static void note(
            final int taskId,
            final Source source,
            final String expectedMode,
            final String detail) {
        if (taskId < 0 || expectedMode == null) {
            return;
        }
        PENDING.remove(Integer.valueOf(taskId));
        PENDING.put(
                Integer.valueOf(taskId),
                new Cause(
                        source,
                        expectedMode,
                        clean(detail),
                        System.currentTimeMillis()));
        while (PENDING.size() > MAX_PENDING_TASKS) {
            final Map.Entry<Integer, Cause> oldest =
                    PENDING.entrySet().iterator().next();
            PENDING.remove(oldest.getKey());
        }
    }

    private static String expectedMode(
            final DesktopWindowTransitionRequest.Operation operation) {
        switch (operation) {
            case ENTER_FULLSCREEN:
            case ENTER_APP_FULLSCREEN:
                return "fullscreen";
            case RESTORE_FREEFORM:
                return "freeform";
            default:
                return null;
        }
    }

    private static String normalizedMode(final String mode) {
        return isKnownMode(mode) ? mode : null;
    }

    private static boolean isKnownMode(final String mode) {
        return "fullscreen".equals(mode) || "freeform".equals(mode);
    }

    private static String clean(final String value) {
        if (value == null || value.isEmpty()) {
            return "unknown";
        }
        return value.replace('\n', ' ').replace('\r', ' ').trim();
    }

    static final class Observation {
        final Source source;
        final String detail;
        final long ageMillis;

        Observation(
                final Source observedSource,
                final String observedDetail,
                final long observedAgeMillis) {
            source = observedSource;
            detail = observedDetail;
            ageMillis = observedAgeMillis;
        }
    }

    private static final class Cause {
        final Source source;
        final String expectedMode;
        final String detail;
        final long timestampMillis;

        Cause(
                final Source transitionSource,
                final String mode,
                final String causeDetail,
                final long timestamp) {
            source = transitionSource;
            expectedMode = mode;
            detail = causeDetail;
            timestampMillis = timestamp;
        }
    }
}
