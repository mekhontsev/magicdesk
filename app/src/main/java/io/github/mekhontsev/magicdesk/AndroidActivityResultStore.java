package io.github.mekhontsev.magicdesk;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.UriPermission;
import android.net.Uri;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Bounded event-driven result registry for Activity result requests. */
final class AndroidActivityResultStore {
    private static final int MAX_RESULTS = 64;
    private static final Object LOCK = new Object();
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static PersistedUriPermissions sUriPermissions;

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

    static String begin(final Intent target) throws JSONException {
        final String id = UUID.randomUUID().toString();
        final ArrayList<Entry> evicted;
        final Entry pending = Entry.pending(id, AndroidActivityResultData.describeIntent(target));
        synchronized (LOCK) {
            ENTRIES.put(id, pending);
            evicted = trimLocked();
        }
        releaseEntries(evicted);
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
        final List<PersistedUriPermissions.Grant> grants = new ArrayList<>();
        final Entry completed;
        try {
            final AndroidActivityResultData result = AndroidActivityResultData.read(data);
            result.json.put("persistedUris", persistReturnedUris(context, result, grants));
            completed = Entry.completed(
                    id, resultCode, result.json, grants);
        } catch (JSONException | RuntimeException error) {
            try {
                releaseGrants(grants);
            } catch (RuntimeException cleanupFailure) {
                if (cleanupFailure != error) {
                    error.addSuppressed(cleanupFailure);
                }
            }
            fail(id, error);
            return;
        }
        final boolean accepted;
        final Entry previous;
        synchronized (LOCK) {
            previous = ENTRIES.get(id);
            accepted = previous != null && previous.isPending();
            if (accepted) {
                ENTRIES.put(id, completed);
                LOCK.notifyAll();
            }
        }
        if (!accepted) {
            releaseEntry(completed);
            return;
        }
        previous.ready.complete(null);
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
        final Entry previous;
        synchronized (LOCK) {
            previous = ENTRIES.get(id);
            if (previous == null || !previous.isPending()) {
                return;
            }
            ENTRIES.put(id, failed);
            LOCK.notifyAll();
        }
        previous.ready.complete(null);
        DesktopAutomationEventJournal.record(
                "android-integration",
                "activity-result-failed",
                false,
                id,
                diagnosticSummary(failed));
    }

    static JSONObject get(
            final String id,
            final long waitMillis,
            final boolean consume) throws JSONException {
        final Entry entry;
        final boolean consumed;
        synchronized (LOCK) {
            final long deadline = waitMillis > 0L
                    ? SystemClock.elapsedRealtime() + waitMillis : 0L;
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
                LOCK.notifyAll();
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
        // Consumption ends ownership even if serializing the response fails.
        final JSONArray released = consumed ? releaseEntry(entry) : null;
        final JSONObject result = entry.toJson();
        if (consumed) {
            result.put("consumed", true)
                    .put("releasedPersistedUris", released);
        }
        return result;
    }

    /** Notifies outside the registry lock; the callback reads the current result. */
    static CompletableFuture<Void> whenReady(final String id, final Runnable callback) {
        final CompletableFuture<Void> ready;
        synchronized (LOCK) {
            final Entry entry = ENTRIES.get(id);
            ready = entry == null ? CompletableFuture.completedFuture(null) : entry.ready;
        }
        return ready.thenRun(callback);
    }

    static void discard(final String id) {
        final Entry entry;
        synchronized (LOCK) {
            entry = ENTRIES.remove(id);
            if (entry != null) {
                LOCK.notifyAll();
            }
        }
        if (entry != null) {
            releaseEntry(entry);
        }
    }

    static ClaimedResult claim(final String id) {
        final Entry entry;
        synchronized (LOCK) {
            entry = ENTRIES.get(id);
            if (entry == null || entry.isPending()) {
                return null;
            }
            ENTRIES.remove(id);
            LOCK.notifyAll();
        }
        return new ClaimedResult(entry);
    }

    /** An import owns this result until its provider I/O has finished. */
    static final class ClaimedResult implements AutoCloseable {
        private final Entry entry;

        ClaimedResult(final Entry entry) {
            this.entry = entry;
        }

        JSONObject toJson() throws JSONException {
            return entry.toJson();
        }

        @Override
        public void close() {
            releaseEntry(entry);
        }
    }

    private static ArrayList<Entry> trimLocked() {
        final ArrayList<Entry> evicted = new ArrayList<>();
        final Iterator<Entry> oldest = ENTRIES.values().iterator();
        while (ENTRIES.size() > MAX_RESULTS && oldest.hasNext()) {
            evicted.add(oldest.next());
            oldest.remove();
        }
        if (!evicted.isEmpty()) {
            LOCK.notifyAll();
        }
        return evicted;
    }

    private static JSONArray persistReturnedUris(
            final Context context,
            final AndroidActivityResultData result,
            final List<PersistedUriPermissions.Grant> grants) {
        final JSONArray persisted = new JSONArray();
        if (context == null
                || (result.grantFlags
                        & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) == 0) {
            return persisted;
        }
        final int modeFlags = result.grantFlags
                & (Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        if (modeFlags == 0) {
            return persisted;
        }
        for (final String uri : result.returnedUris) {
            if (!"content".equalsIgnoreCase(Uri.parse(uri).getScheme())) {
                continue;
            }
            try {
                grants.add(uriPermissions(context).acquire(uri, modeFlags));
                persisted.put(uri);
            } catch (SecurityException | UnsupportedOperationException ignored) {
                // The provider may advertise a transient grant only.
            }
        }
        return persisted;
    }

    private static void releaseEntries(
            final ArrayList<Entry> entries) {
        for (final Entry entry : entries) {
            try {
                releaseEntry(entry);
            } catch (RuntimeException error) {
                // Eviction has committed; cleanup must not orphan the new request.
                DesktopAutomationEventJournal.record(
                        "android-integration", "evicted-result-grant-release-failed",
                        false, error.getClass().getSimpleName(), diagnosticSummary(entry));
            }
        }
    }

    private static JSONArray releaseEntry(final Entry entry) {
        try {
            return releaseGrants(entry.grants);
        } finally {
            // Discard/eviction also ends a pending subscription. Late Activity
            // results cannot recreate an entry after its owner has left.
            entry.ready.complete(null);
        }
    }

    private static JSONArray releaseGrants(
            final List<PersistedUriPermissions.Grant> grants) {
        final JSONArray released = new JSONArray();
        RuntimeException failure = null;
        for (final PersistedUriPermissions.Grant grant : grants) {
            try {
                if (grant.release()) {
                    released.put(grant.uri);
                }
            } catch (SecurityException | IllegalArgumentException
                    | UnsupportedOperationException ignored) {
                // Android or the provider may already have revoked access.
            } catch (RuntimeException error) {
                if (failure == null) {
                    failure = error;
                } else if (failure != error) {
                    failure.addSuppressed(error);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
        return released;
    }

    private static synchronized PersistedUriPermissions uriPermissions(
            final Context context) {
        if (sUriPermissions == null) {
            final Context application = context.getApplicationContext();
            sUriPermissions = new PersistedUriPermissions(
                    new PersistedUriPermissions.Access() {
                        @Override
                        public void take(final String uri, final int flags) {
                            takePersistedUri(application, Uri.parse(uri), flags);
                        }

                        @Override
                        public void release(final String uri, final int flags) {
                            releasePersistedUri(application, Uri.parse(uri), flags);
                        }
                    });
        }
        return sUriPermissions;
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

    static final class Entry {
        final String requestId;
        final String state;
        final long timestampMillis;
        final Integer resultCode;
        final String error;
        private final JSONObject data;
        final List<PersistedUriPermissions.Grant> grants;
        private final CompletableFuture<Void> ready;

        Entry(
                final String requestId,
                final String state,
                final Integer resultCode,
                final String error,
                final JSONObject data,
                final List<PersistedUriPermissions.Grant> grants) {
            this.requestId = requestId;
            this.state = state;
            this.timestampMillis = System.currentTimeMillis();
            this.resultCode = resultCode;
            this.error = error == null ? "" : error;
            try {
                this.data = data == null ? new JSONObject() : new JSONObject(data.toString());
            } catch (JSONException invalidData) {
                throw new IllegalArgumentException("Activity result data is not valid JSON", invalidData);
            }
            this.grants = List.copyOf(grants);
            ready = isPending() ? new CompletableFuture<>() : CompletableFuture.completedFuture(null);
        }

        static Entry pending(final String id, final JSONObject data) {
            return new Entry(id, "pending", null, "", data, List.of());
        }

        static Entry completed(
                final String id,
                final int resultCode,
                final JSONObject data,
                final List<PersistedUriPermissions.Grant> grants) {
            return new Entry(
                    id, "completed", Integer.valueOf(resultCode), "", data, grants);
        }

        static Entry failed(final String id, final String error) {
            return new Entry(id, "failed", null, error, null, List.of());
        }

        boolean isPending() {
            return "pending".equals(state);
        }

        JSONObject toJson() throws JSONException {
            final JSONObject result = new JSONObject()
                    .put("requestId", requestId)
                    .put("state", state)
                    .put("timestampMillis", timestampMillis)
                    .put("data", new JSONObject(data.toString()));
            if (resultCode != null) {
                result.put("resultCode", resultCode.intValue());
            }
            if (!error.isEmpty()) {
                result.put("error", error);
            }
            return result;
        }
    }
}
