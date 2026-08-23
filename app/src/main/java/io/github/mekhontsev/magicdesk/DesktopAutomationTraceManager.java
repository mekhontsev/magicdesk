package io.github.mekhontsev.magicdesk;

import android.view.Display;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Defines bounded operation traces as ranges in the shared event journal. */
final class DesktopAutomationTraceManager {
    private static final int MAX_ACTIVE_TRACES = 16;

    private final DesktopAutomationStateReader mState;
    private final Map<String, TraceStart> mActive = new LinkedHashMap<>();

    DesktopAutomationTraceManager(final DesktopAutomationStateReader state) {
        mState = state;
    }

    DesktopAutomationResult begin(final JSONObject arguments)
            throws JSONException {
        final JSONObject args = arguments == null
                ? new JSONObject() : arguments;
        final int displayId = args.has("displayId")
                ? requiredInt(args, "displayId") : Display.INVALID_DISPLAY;
        final String traceId = UUID.randomUUID().toString();
        final TraceStart start = new TraceStart(
                traceId,
                System.currentTimeMillis(),
                DesktopAutomationEventJournal.latestId(),
                displayId,
                args.optString("label", "").trim());
        synchronized (mActive) {
            while (mActive.size() >= MAX_ACTIVE_TRACES) {
                final String oldest = mActive.keySet().iterator().next();
                mActive.remove(oldest);
            }
            mActive.put(traceId, start);
        }
        return DesktopAutomationResult.success(
                "trace started", start.toJson());
    }

    DesktopAutomationResult end(final JSONObject arguments)
            throws JSONException {
        final JSONObject args = arguments == null
                ? new JSONObject() : arguments;
        final String traceId = requiredString(args, "traceId");
        final TraceStart start;
        synchronized (mActive) {
            start = mActive.remove(traceId);
        }
        if (start == null) {
            return DesktopAutomationResult.failure(
                    DesktopAutomationErrorCode.INVALID_ARGUMENT,
                    "unknown or expired traceId", false);
        }
        final long endedAtMillis = System.currentTimeMillis();
        final long latestEventId = DesktopAutomationEventJournal.latestId();
        final JSONArray events = DesktopAutomationEventJournal.snapshot(
                start.afterEventId, 256);
        final long firstEventId = events.length() == 0
                ? latestEventId + 1L
                : events.getJSONObject(0).optLong("id", latestEventId + 1L);
        final boolean truncated = firstEventId
                > start.afterEventId + 1L;
        final JSONArray failures = new JSONArray();
        for (int index = 0; index < events.length(); index++) {
            final JSONObject event = events.optJSONObject(index);
            if (event != null
                    && (!event.optBoolean("success", true)
                            || "process".equals(event.optString("type")))) {
                failures.put(event);
            }
        }
        final JSONObject taskArguments = new JSONObject();
        if (start.displayId >= Display.DEFAULT_DISPLAY) {
            taskArguments.put("displayId", start.displayId);
        }
        return DesktopAutomationResult.success(
                "trace completed",
                new JSONObject()
                        .put("traceId", start.traceId)
                        .put("label", start.label)
                        .put("startedAtMillis", start.startedAtMillis)
                        .put("endedAtMillis", endedAtMillis)
                        .put("afterEventId", start.afterEventId)
                        .put("latestEventId", latestEventId)
                        .put("truncated", truncated)
                        .put("eventCount", events.length())
                        .put("failureCount", failures.length())
                        .put("displayId", start.displayId
                                >= Display.DEFAULT_DISPLAY
                                        ? start.displayId : JSONObject.NULL)
                        .put("events", events)
                        .put("failures", failures)
                        .put("state", mState.state())
                        .put("tasks", mState.tasks(taskArguments)));
    }

    private static String requiredString(
            final JSONObject object, final String key) {
        final String value = object == null
                ? "" : object.optString(key, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(key + " is required");
        }
        return value;
    }

    private static int requiredInt(
            final JSONObject object, final String key) {
        final Object value = object == null ? null : object.opt(key);
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(key + " must be an integer");
        }
        final long number = ((Number) value).longValue();
        if (number < Integer.MIN_VALUE || number > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(key + " is out of range");
        }
        return (int) number;
    }

    private static final class TraceStart {
        final String traceId;
        final long startedAtMillis;
        final long afterEventId;
        final int displayId;
        final String label;

        TraceStart(
                final String traceId,
                final long startedAtMillis,
                final long afterEventId,
                final int displayId,
                final String label) {
            this.traceId = traceId;
            this.startedAtMillis = startedAtMillis;
            this.afterEventId = afterEventId;
            this.displayId = displayId;
            this.label = label;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("traceId", traceId)
                    .put("label", label)
                    .put("startedAtMillis", startedAtMillis)
                    .put("afterEventId", afterEventId)
                    .put("displayId", displayId >= Display.DEFAULT_DISPLAY
                            ? displayId : JSONObject.NULL);
        }
    }
}
