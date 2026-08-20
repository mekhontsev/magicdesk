package io.github.mekhontsev.magicdesk;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** Bounded process-local history for structured automation operations. */
final class DesktopAutomationEventJournal {
    private static final int MAX_EVENTS = 256;
    private static final int MAX_DETAIL_CHARS = 1_000;
    private static final Object LOCK = new Object();
    private static final ArrayDeque<Event> EVENTS = new ArrayDeque<>();
    private static final AtomicLong NEXT_ID = new AtomicLong();

    private DesktopAutomationEventJournal() {
    }

    static long record(
            final String type,
            final String operation,
            final boolean success,
            final String detail) {
        final Event event = new Event(
                NEXT_ID.incrementAndGet(),
                System.currentTimeMillis(),
                clean(type),
                clean(operation),
                success,
                clean(detail));
        synchronized (LOCK) {
            EVENTS.addLast(event);
            while (EVENTS.size() > MAX_EVENTS) {
                EVENTS.removeFirst();
            }
        }
        return event.id;
    }

    static JSONArray snapshot(final long afterId, final int requestedLimit)
            throws JSONException {
        final int limit = Math.max(1, Math.min(MAX_EVENTS, requestedLimit));
        final List<Event> copy;
        synchronized (LOCK) {
            copy = new ArrayList<>(EVENTS);
        }
        final JSONArray result = new JSONArray();
        final int first = Math.max(0, copy.size() - limit);
        for (int index = first; index < copy.size(); index++) {
            final Event event = copy.get(index);
            if (event.id > afterId) {
                result.put(event.toJson());
            }
        }
        return result;
    }

    static long latestId() {
        return NEXT_ID.get();
    }

    private static String clean(final String value) {
        if (value == null) {
            return "";
        }
        final String normalized = value.replace('\u0000', ' ')
                .replace('\r', ' ')
                .replace('\n', ' ')
                .trim();
        return normalized.length() <= MAX_DETAIL_CHARS
                ? normalized : normalized.substring(0, MAX_DETAIL_CHARS);
    }

    private static final class Event {
        final long id;
        final long timestampMillis;
        final String type;
        final String operation;
        final boolean success;
        final String detail;

        Event(
                final long id,
                final long timestampMillis,
                final String type,
                final String operation,
                final boolean success,
                final String detail) {
            this.id = id;
            this.timestampMillis = timestampMillis;
            this.type = type;
            this.operation = operation;
            this.success = success;
            this.detail = detail;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("id", id)
                    .put("timestampMillis", timestampMillis)
                    .put("type", type)
                    .put("operation", operation)
                    .put("success", success)
                    .put("detail", detail);
        }
    }
}
