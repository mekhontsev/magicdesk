package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
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

    static void releaseOrphanedPersistedUris(final Context context) {
        if (context == null) {
            return;
        }
        final List<UriPermission> permissions;
        try {
            permissions = context.getContentResolver()
                    .getPersistedUriPermissions();
        } catch (RuntimeException error) {
            DesktopAutomationEventJournal.record(
                    "android-integration",
                    "orphaned-result-grants-released",
                    false,
                    ShellAccess.usefulMessage(error),
                    null);
            return;
        }
        int released = 0;
        for (final UriPermission permission : permissions) {
            int modeFlags = 0;
            if (permission.isReadPermission()) {
                modeFlags |= Intent.FLAG_GRANT_READ_URI_PERMISSION;
            }
            if (permission.isWritePermission()) {
                modeFlags |= Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            }
            if (modeFlags == 0) {
                continue;
            }
            try {
                releasePersistedUri(context, permission.getUri(), modeFlags);
                released++;
            } catch (SecurityException | IllegalArgumentException
                    | UnsupportedOperationException ignored) {
                // The provider or Android may already have removed the grant.
            }
        }
        DesktopAutomationEventJournal.record(
                "android-integration",
                "orphaned-result-grants-released",
                true,
                "count=" + released,
                null);
    }

    static String begin(final Context context, final Intent target) {
        if (context == null) {
            throw new IllegalArgumentException("result context is required");
        }
        final String id = UUID.randomUUID().toString();
        final ArrayList<Entry> evicted;
        final Entry pending = Entry.pending(id, describeIntent(target));
        synchronized (LOCK) {
            ENTRIES.put(id, pending);
            ORDER.addLast(id);
            evicted = trimLocked();
        }
        releaseEntries(context, evicted);
        DesktopAutomationEventJournal.record(
                "android-integration",
                "activity-result-pending",
                true,
                id,
                diagnosticSummary(pending));
        return id;
    }

    static void complete(
            final Context context,
            final String id,
            final int resultCode,
            final Intent data) {
        synchronized (LOCK) {
            final Entry pending = ENTRIES.get(id);
            if (pending == null || !pending.isPending()) {
                return;
            }
        }
        final Entry completed = Entry.completed(
                id, resultCode, describeResult(context, data));
        synchronized (LOCK) {
            final Entry pending = ENTRIES.get(id);
            if (pending == null || !pending.isPending()) {
                releaseEntry(context, completed);
                return;
            }
            ENTRIES.put(id, completed);
            LOCK.notifyAll();
        }
        DesktopAutomationEventJournal.record(
                "android-integration",
                "activity-result-completed",
                true,
                id,
                diagnosticSummary(completed));
    }

    static void fail(final String id, final Throwable error) {
        final String message = ShellAccess.usefulMessage(error);
        final Entry failed = Entry.failed(id, message);
        synchronized (LOCK) {
            final Entry pending = ENTRIES.get(id);
            if (pending == null || !pending.isPending()) {
                return;
            }
            ENTRIES.put(id, failed);
            LOCK.notifyAll();
        }
        DesktopAutomationEventJournal.record(
                "android-integration",
                "activity-result-failed",
                false,
                id,
                diagnosticSummary(failed));
    }

    static JSONObject get(
            final Context context,
            final String id,
            final long waitMillis,
            final boolean consume) throws JSONException {
        final Entry entry;
        final boolean consumed;
        synchronized (LOCK) {
            final long deadline = SystemClock.elapsedRealtime()
                    + Math.max(0L, waitMillis);
            Entry current = ENTRIES.get(id);
            while (current != null
                    && "pending".equals(current.state)
                    && waitMillis > 0L) {
                final long remaining = deadline - SystemClock.elapsedRealtime();
                if (remaining <= 0L) {
                    break;
                }
                try {
                    EventDrivenWaits.await(
                            LOCK,
                            EventDrivenWaits.Reason.ACTIVITY_LAUNCH_RESULT,
                            remaining);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    break;
                }
                current = ENTRIES.get(id);
            }
            entry = current;
            if (consume && entry != null
                    && !entry.isPending()) {
                ENTRIES.remove(id);
                ORDER.remove(id);
                consumed = true;
            } else {
                consumed = false;
            }
        }
        if (entry == null) {
            return new JSONObject()
                    .put("requestId", id)
                    .put("state", "not_found");
        }
        final JSONObject result = entry.toJson();
        if (consumed) {
            result.put("consumed", true)
                    .put("releasedPersistedUris",
                            releaseEntry(context, entry));
        }
        return result;
    }

    private static ArrayList<Entry> trimLocked() {
        final ArrayList<Entry> evicted = new ArrayList<>();
        while (ORDER.size() > MAX_RESULTS) {
            final Entry entry = ENTRIES.remove(ORDER.removeFirst());
            if (entry != null) {
                evicted.add(entry);
            }
        }
        return evicted;
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

    private static JSONObject describeResult(
            final Context context,
            final Intent intent) {
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
                    .put("grantFlags", intent.getFlags()
                            & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                                    | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION))
                    .put("persistedUris", persistReturnedUris(context, intent))
                    .put("extras", scalarExtras(intent.getExtras()));
        } catch (JSONException ignored) {
        }
        return data;
    }

    private static JSONArray persistReturnedUris(
            final Context context,
            final Intent intent) {
        final JSONArray persisted = new JSONArray();
        if (context == null || intent == null
                || (intent.getFlags()
                        & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0) {
            return persisted;
        }
        final int modeFlags = intent.getFlags()
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (modeFlags == 0) {
            return persisted;
        }
        final ArrayList<Uri> uris = new ArrayList<>();
        addUri(uris, intent.getData());
        final ClipData clip = intent.getClipData();
        if (clip != null) {
            for (int index = 0;
                    index < clip.getItemCount() && index < MAX_RESULT_URIS;
                    index++) {
                addUri(uris, clip.getItemAt(index).getUri());
            }
        }
        for (final Uri uri : uris) {
            try {
                takePersistedUri(context, uri, modeFlags);
                persisted.put(uri.toString());
            } catch (SecurityException | UnsupportedOperationException ignored) {
                // The provider may advertise a transient grant only.
            }
        }
        return persisted;
    }

    private static void releaseEntries(
            final Context context,
            final ArrayList<Entry> entries) {
        for (final Entry entry : entries) {
            releaseEntry(context, entry);
        }
    }

    private static JSONArray releaseEntry(
            final Context context,
            final Entry entry) {
        final JSONArray released = new JSONArray();
        if (context == null || entry == null) {
            return released;
        }
        final JSONArray persisted = entry.data.optJSONArray("persistedUris");
        final int modeFlags = entry.data.optInt("grantFlags", 0)
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (persisted == null || modeFlags == 0) {
            return released;
        }
        for (int index = 0; index < persisted.length(); index++) {
            final String value = persisted.optString(index, "");
            if (value.isEmpty()) {
                continue;
            }
            try {
                releasePersistedUri(context, Uri.parse(value), modeFlags);
                released.put(value);
            } catch (SecurityException | IllegalArgumentException
                    | UnsupportedOperationException ignored) {
                // Another owner may already have released the grant.
            }
        }
        return released;
    }

    @SuppressLint("WrongConstant")
    private static void takePersistedUri(
            final Context context,
            final Uri uri,
            final int modeFlags) {
        context.getContentResolver().takePersistableUriPermission(
                uri, modeFlags);
    }

    @SuppressLint("WrongConstant")
    private static void releasePersistedUri(
            final Context context,
            final Uri uri,
            final int modeFlags) {
        context.getContentResolver().releasePersistableUriPermission(
                uri, modeFlags);
    }

    private static JSONObject diagnosticSummary(final Entry entry) {
        final JSONObject summary = new JSONObject();
        if (entry == null) {
            return summary;
        }
        try {
            summary.put("requestId", entry.requestId)
                    .put("state", entry.state)
                    .put("resultCode", entry.resultCode == null
                            ? JSONObject.NULL : entry.resultCode)
                    .put("hasDataUri",
                            !entry.data.optString("dataUri", "").isEmpty())
                    .put("clipUriCount", length(
                            entry.data.optJSONArray("clipUris")))
                    .put("persistedUriCount", length(
                            entry.data.optJSONArray("persistedUris")));
        } catch (JSONException ignored) {
        }
        return summary;
    }

    private static int length(final JSONArray values) {
        return values == null ? 0 : values.length();
    }

    private static void addUri(final ArrayList<Uri> uris, final Uri uri) {
        if (uri != null
                && "content".equalsIgnoreCase(uri.getScheme())
                && !uris.contains(uri)) {
            uris.add(uri);
        }
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

        boolean isPending() {
            return "pending".equals(state);
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
