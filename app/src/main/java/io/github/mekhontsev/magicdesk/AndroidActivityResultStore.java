package io.github.mekhontsev.magicdesk;

import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Bounded event-driven result registry for MCP Activity result requests. */
final class AndroidActivityResultStore {
    private static final int MAX_RESULTS = 64;
    private static final int MAX_RESULT_URIS = 32;
    private static final int MAX_EXTRAS = 32;
    private static final int MAX_TEXT_CHARS = 8_192;
    private static final Object LOCK = new Object();
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static final ArrayDeque<String> ORDER = new ArrayDeque<>();

    private AndroidActivityResultStore() {
    }

    static String begin(final Intent target) {
        final String id = UUID.randomUUID().toString();
        synchronized (LOCK) {
            ENTRIES.put(id, Entry.pending(id, describeIntent(target)));
            ORDER.addLast(id);
            trimLocked();
        }
        DesktopAutomationEventJournal.record(
                "android-integration",
                "activity-result-pending",
                true,
                id,
                describeIntent(target));
        return id;
    }

    static void complete(
            final String id,
            final int resultCode,
            final Intent data) {
        final Entry completed = Entry.completed(
                id, resultCode, describeResult(data));
        synchronized (LOCK) {
            if (!ENTRIES.containsKey(id)) {
                return;
            }
            ENTRIES.put(id, completed);
        }
        DesktopAutomationEventJournal.record(
                "android-integration",
                "activity-result-completed",
                true,
                id,
                completed.toJson());
    }

    static void fail(final String id, final Throwable error) {
        final String message = ShellAccess.usefulMessage(error);
        final Entry failed = Entry.failed(id, message);
        synchronized (LOCK) {
            if (!ENTRIES.containsKey(id)) {
                return;
            }
            ENTRIES.put(id, failed);
        }
        DesktopAutomationEventJournal.record(
                "android-integration",
                "activity-result-failed",
                false,
                message,
                failed.toJson());
    }

    static JSONObject get(final String id) throws JSONException {
        final Entry entry;
        synchronized (LOCK) {
            entry = ENTRIES.get(id);
        }
        if (entry == null) {
            return new JSONObject()
                    .put("requestId", id)
                    .put("state", "not_found");
        }
        return entry.toJson();
    }

    private static void trimLocked() {
        while (ORDER.size() > MAX_RESULTS) {
            ENTRIES.remove(ORDER.removeFirst());
        }
    }

    private static JSONObject describeIntent(final Intent intent) {
        final JSONObject data = new JSONObject();
        if (intent == null) {
            return data;
        }
        try {
            data.put("action", value(intent.getAction()))
                    .put("dataUri", value(intent.getDataString()))
                    .put("mimeType", value(intent.getType()))
                    .put("component", intent.getComponent() == null
                            ? "" : intent.getComponent().flattenToShortString())
                    .put("package", value(intent.getPackage()));
        } catch (JSONException ignored) {
        }
        return data;
    }

    private static JSONObject describeResult(final Intent intent) {
        final JSONObject data = describeIntent(intent);
        if (intent == null) {
            return data;
        }
        try {
            final ClipData clip = intent.getClipData();
            final JSONArray clipUris = new JSONArray();
            if (clip != null) {
                for (int index = 0;
                        index < clip.getItemCount()
                                && index < MAX_RESULT_URIS;
                        index++) {
                    final Uri uri = clip.getItemAt(index).getUri();
                    if (uri != null) {
                        clipUris.put(value(uri.toString()));
                    }
                }
            }
            data.put("clipUris", clipUris)
                    .put("clipUrisTruncated", clip != null
                            && clip.getItemCount() > MAX_RESULT_URIS)
                    .put("extras", scalarExtras(intent.getExtras()));
        } catch (JSONException ignored) {
        }
        return data;
    }

    private static JSONObject scalarExtras(final Bundle extras)
            throws JSONException {
        final JSONObject result = new JSONObject();
        if (extras == null) {
            return result;
        }
        int count = 0;
        final Iterator<String> names = extras.keySet().iterator();
        while (names.hasNext() && count < MAX_EXTRAS) {
            final String name = names.next();
            final Object item;
            try {
                item = extras.get(name);
            } catch (RuntimeException ignored) {
                continue;
            }
            if (item == null
                    || item instanceof String
                    || item instanceof Boolean
                    || item instanceof Number) {
                result.put(value(name), item == null
                        ? JSONObject.NULL
                        : item instanceof String
                                ? value((String) item) : item);
                count++;
            } else if (item instanceof CharSequence) {
                result.put(value(name), value(item.toString()));
                count++;
            } else if (item instanceof Uri) {
                result.put(value(name), value(item.toString()));
                count++;
            }
        }
        return result;
    }

    private static String value(final String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_TEXT_CHARS
                ? value : value.substring(0, MAX_TEXT_CHARS);
    }

    private static final class Entry {
        final String requestId;
        final String state;
        final long timestampMillis;
        final Integer resultCode;
        final String error;
        final JSONObject data;

        Entry(
                final String requestId,
                final String state,
                final Integer resultCode,
                final String error,
                final JSONObject data) {
            this.requestId = requestId;
            this.state = state;
            this.timestampMillis = System.currentTimeMillis();
            this.resultCode = resultCode;
            this.error = error == null ? "" : error;
            this.data = data == null ? new JSONObject() : data;
        }

        static Entry pending(final String id, final JSONObject data) {
            return new Entry(id, "pending", null, "", data);
        }

        static Entry completed(
                final String id,
                final int resultCode,
                final JSONObject data) {
            return new Entry(
                    id, "completed", Integer.valueOf(resultCode), "", data);
        }

        static Entry failed(final String id, final String error) {
            return new Entry(id, "failed", null, error, null);
        }

        JSONObject toJson() {
            final JSONObject result = new JSONObject();
            try {
                result.put("requestId", requestId)
                        .put("state", state)
                        .put("timestampMillis", timestampMillis)
                        .put("data", data);
                if (resultCode != null) {
                    result.put("resultCode", resultCode.intValue());
                }
                if (!error.isEmpty()) {
                    result.put("error", error);
                }
            } catch (JSONException ignored) {
            }
            return result;
        }
    }
}
