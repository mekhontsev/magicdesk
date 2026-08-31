package io.github.mekhontsev.magicdesk;

import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.PersistableBundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** The single app-identity boundary for Android's system clipboard. */
final class AndroidClipboardGateway {
    private static final String EXTRA_FILE_CLIP_OWNER =
            "io.github.mekhontsev.magicdesk.FILE_CLIP_OWNER";
    private static final String EXTRA_FILE_CLIP_SESSION =
            "io.github.mekhontsev.magicdesk.FILE_CLIP_SESSION";
    private static final String EXTRA_FILE_CLIP_GENERATION =
            "io.github.mekhontsev.magicdesk.FILE_CLIP_GENERATION";
    private static final String FILE_CLIP_OWNER = "magicdesk";
    private static final String PROCESS_SESSION = UUID.randomUUID().toString();
    private static final int MAX_PUBLISHED_URI_ITEMS = 64;
    private static final Object INSTANCE_LOCK = new Object();
    private static final Object RUNTIME_LOCK = new Object();

    private static volatile AndroidClipboardGateway sInstance;
    private static long sReads;
    private static long sTextWrites;
    private static long sUriWrites;
    private static long sClears;
    private static long sFailures;
    private static String sLastOperation = "none";
    private static String sLastFailure = "none";

    enum Access {
        AVAILABLE("available"),
        EMPTY("empty"),
        DENIED("denied"),
        UNAVAILABLE("unavailable"),
        FAILED("failed");

        final String wireName;

        Access(final String wireName) {
            this.wireName = wireName;
        }
    }

    static final class Metadata {
        final Access access;
        final int itemCount;
        final List<String> mimeTypes;
        final boolean sensitive;
        final String fileSession;
        final long fileGeneration;
        final String error;

        Metadata(
                final Access access,
                final int itemCount,
                final List<String> mimeTypes,
                final boolean sensitive,
                final String fileSession,
                final long fileGeneration,
                final String error) {
            this.access = access;
            this.itemCount = itemCount;
            this.mimeTypes = mimeTypes;
            this.sensitive = sensitive;
            this.fileSession = fileSession;
            this.fileGeneration = fileGeneration;
            this.error = error == null ? "" : error;
        }

        boolean belongsToFileOperation(final long generation) {
            return generation >= 0L
                    && PROCESS_SESSION.equals(fileSession)
                    && generation == fileGeneration;
        }

        boolean mayContainUris() {
            for (final String mimeType : mimeTypes) {
                if (ClipDescription.MIMETYPE_TEXT_URILIST.equals(mimeType)
                        || (!ClipDescription.MIMETYPE_TEXT_PLAIN.equals(mimeType)
                        && !ClipDescription.MIMETYPE_TEXT_HTML.equals(mimeType)
                        && !ClipDescription.MIMETYPE_TEXT_INTENT.equals(mimeType))) {
                    return true;
                }
            }
            return false;
        }
    }

    static final class OperationResult {
        final boolean successful;
        final String error;
        final Metadata metadata;

        OperationResult(
                final boolean successful,
                final String error,
                final Metadata metadata) {
            this.successful = successful;
            this.error = error == null ? "" : error;
            this.metadata = metadata;
        }
    }

    static final class UriItem {
        final Uri uri;
        final String mimeType;

        UriItem(final Uri uri, final String mimeType) {
            if (uri == null) {
                throw new IllegalArgumentException("clipboard URI is required");
            }
            this.uri = uri;
            this.mimeType = safeMimeType(mimeType);
        }
    }

    static final class TextReadResult {
        final Metadata metadata;
        final String text;

        TextReadResult(final Metadata metadata, final String text) {
            this.metadata = metadata;
            this.text = text == null ? "" : text;
        }
    }

    static final class UriReadResult {
        final Metadata metadata;
        final List<Uri> uris;

        UriReadResult(final Metadata metadata, final List<Uri> uris) {
            this.metadata = metadata;
            this.uris = uris;
        }
    }

    private final ClipboardManager mClipboard;

    private AndroidClipboardGateway(final Context context) {
        final Context applicationContext = context.getApplicationContext();
        final Context owner = applicationContext == null
                ? context : applicationContext;
        mClipboard = owner.getSystemService(ClipboardManager.class);
    }

    static AndroidClipboardGateway get(final Context context) {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        AndroidClipboardGateway instance = sInstance;
        if (instance != null) {
            return instance;
        }
        synchronized (INSTANCE_LOCK) {
            if (sInstance == null) {
                sInstance = new AndroidClipboardGateway(context);
            }
            return sInstance;
        }
    }

    static boolean acceptsUriItemCount(final int count) {
        return count > 0 && count <= MAX_PUBLISHED_URI_ITEMS;
    }

    OperationResult writeText(
            final CharSequence label,
            final CharSequence text,
            final boolean sensitive) {
        if (text == null) {
            return failure("write_text", "text is required");
        }
        final ClipData clip = ClipData.newPlainText(safeLabel(label), text);
        if (sensitive) {
            final PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        return setPrimaryClip(clip, "write_text", false);
    }

    OperationResult writeUris(
            final CharSequence label,
            final List<UriItem> items,
            final long fileGeneration) {
        if (items == null || items.isEmpty()) {
            return failure("write_uris", "at least one URI is required");
        }
        if (!acceptsUriItemCount(items.size())) {
            return failure(
                    "write_uris",
                    "URI item count exceeds " + MAX_PUBLISHED_URI_ITEMS);
        }
        final Set<String> mimeTypes = new LinkedHashSet<>();
        mimeTypes.add(ClipDescription.MIMETYPE_TEXT_URILIST);
        for (final UriItem item : items) {
            if (item == null) {
                return failure("write_uris", "clipboard item is required");
            }
            mimeTypes.add(item.mimeType);
        }
        final ClipDescription description = new ClipDescription(
                safeLabel(label), mimeTypes.toArray(new String[0]));
        final ClipData clip = new ClipData(
                description, new ClipData.Item(items.get(0).uri));
        for (int index = 1; index < items.size(); index++) {
            clip.addItem(new ClipData.Item(items.get(index).uri));
        }
        if (fileGeneration >= 0L) {
            final PersistableBundle extras = new PersistableBundle();
            extras.putString(EXTRA_FILE_CLIP_OWNER, FILE_CLIP_OWNER);
            extras.putString(EXTRA_FILE_CLIP_SESSION, PROCESS_SESSION);
            extras.putLong(EXTRA_FILE_CLIP_GENERATION, fileGeneration);
            description.setExtras(extras);
        }
        return setPrimaryClip(clip, "write_uris", true);
    }

    TextReadResult readText() {
        final ClipRead read = readPrimaryClip("read_text");
        if (read.clip == null) {
            return new TextReadResult(read.metadata, "");
        }
        try {
            final CharSequence value = read.clip.getItemAt(0).getText();
            return new TextReadResult(
                    read.metadata, value == null ? "" : value.toString());
        } catch (SecurityException error) {
            return new TextReadResult(denied("read_text", error), "");
        } catch (RuntimeException error) {
            return new TextReadResult(failed("read_text", error), "");
        }
    }

    UriReadResult readUris() {
        final ClipRead read = readPrimaryClip("read_uris");
        if (read.clip == null) {
            return new UriReadResult(
                    read.metadata, Collections.emptyList());
        }
        try {
            final Set<Uri> uris = new LinkedHashSet<>();
            for (int index = 0; index < read.clip.getItemCount(); index++) {
                final Uri uri = read.clip.getItemAt(index).getUri();
                if (uri != null) {
                    uris.add(uri);
                }
            }
            return new UriReadResult(
                    read.metadata,
                    Collections.unmodifiableList(new ArrayList<>(uris)));
        } catch (SecurityException error) {
            return new UriReadResult(
                    denied("read_uris", error), Collections.emptyList());
        } catch (RuntimeException error) {
            return new UriReadResult(
                    failed("read_uris", error), Collections.emptyList());
        }
    }

    Metadata metadata() {
        if (mClipboard == null) {
            return unavailable("clipboard service is unavailable");
        }
        try {
            final ClipDescription description =
                    mClipboard.getPrimaryClipDescription();
            if (description == null) {
                return isProcessForeground()
                        ? emptyMetadata()
                        : denied(
                                "read_metadata",
                                "clipboard access requires a focused MagicDesk window");
            }
            return metadata(description, -1);
        } catch (SecurityException error) {
            return denied("read_metadata", error);
        } catch (RuntimeException error) {
            return failed("read_metadata", error);
        }
    }

    OperationResult clear() {
        if (mClipboard == null) {
            return failure("clear", "clipboard service is unavailable");
        }
        try {
            mClipboard.clearPrimaryClip();
            recordSuccess("clear", false, false, true);
            return new OperationResult(true, "", emptyMetadata());
        } catch (RuntimeException error) {
            return failure("clear", error);
        }
    }

    void clearFileOperation(final long generation) {
        final Metadata current = metadata();
        if (current.belongsToFileOperation(generation)) {
            clear();
        }
    }

    static String runtimeDiagnostics() {
        synchronized (RUNTIME_LOCK) {
            return "reads=" + sReads
                    + ", textWrites=" + sTextWrites
                    + ", uriWrites=" + sUriWrites
                    + ", clears=" + sClears
                    + ", failures=" + sFailures
                    + ", lastOperation=" + sLastOperation
                    + ", lastFailure=" + sLastFailure;
        }
    }

    private OperationResult setPrimaryClip(
            final ClipData clip,
            final String operation,
            final boolean uriWrite) {
        if (mClipboard == null) {
            return failure(operation, "clipboard service is unavailable");
        }
        try {
            mClipboard.setPrimaryClip(clip);
            recordSuccess(operation, !uriWrite, uriWrite, false);
            return new OperationResult(
                    true,
                    "",
                    metadata(clip.getDescription(), clip.getItemCount()));
        } catch (RuntimeException error) {
            return failure(operation, error);
        }
    }

    private ClipRead readPrimaryClip(final String operation) {
        if (mClipboard == null) {
            return new ClipRead(
                    unavailable("clipboard service is unavailable"), null);
        }
        try {
            final ClipData clip = mClipboard.getPrimaryClip();
            synchronized (RUNTIME_LOCK) {
                sReads++;
                sLastOperation = operation;
            }
            if (clip == null || clip.getItemCount() == 0) {
                return new ClipRead(
                        isProcessForeground()
                                ? emptyMetadata()
                                : denied(
                                        operation,
                                        "clipboard access requires a focused MagicDesk window"),
                        null);
            }
            return new ClipRead(
                    metadata(clip.getDescription(), clip.getItemCount()),
                    clip);
        } catch (SecurityException error) {
            return new ClipRead(denied(operation, error), null);
        } catch (RuntimeException error) {
            return new ClipRead(failed(operation, error), null);
        }
    }

    private static Metadata metadata(
            final ClipDescription description,
            final int itemCount) {
        if (description == null) {
            return new Metadata(
                    Access.AVAILABLE,
                    itemCount,
                    Collections.emptyList(),
                    false,
                    "",
                    -1L,
                    "");
        }
        final List<String> mimeTypes = new ArrayList<>();
        for (int index = 0; index < description.getMimeTypeCount(); index++) {
            mimeTypes.add(description.getMimeType(index));
        }
        final PersistableBundle extras = description.getExtras();
        final boolean sensitive = extras != null
                && extras.getBoolean(ClipDescription.EXTRA_IS_SENSITIVE, false);
        final boolean owned = extras != null
                && FILE_CLIP_OWNER.equals(
                        extras.getString(EXTRA_FILE_CLIP_OWNER, ""));
        return new Metadata(
                Access.AVAILABLE,
                itemCount,
                Collections.unmodifiableList(mimeTypes),
                sensitive,
                owned ? extras.getString(EXTRA_FILE_CLIP_SESSION, "") : "",
                owned ? extras.getLong(EXTRA_FILE_CLIP_GENERATION, -1L) : -1L,
                "");
    }

    private static Metadata emptyMetadata() {
        return new Metadata(
                Access.EMPTY,
                0,
                Collections.emptyList(),
                false,
                "",
                -1L,
                "");
    }

    private static Metadata unavailable(final String message) {
        return new Metadata(
                Access.UNAVAILABLE,
                0,
                Collections.emptyList(),
                false,
                "",
                -1L,
                message);
    }

    private static Metadata denied(
            final String operation, final RuntimeException error) {
        recordFailure(operation, error);
        return new Metadata(
                Access.DENIED,
                0,
                Collections.emptyList(),
                false,
                "",
                -1L,
                ShellAccess.usefulMessage(error));
    }

    private static Metadata denied(
            final String operation, final String message) {
        recordFailure(operation, message);
        return new Metadata(
                Access.DENIED,
                0,
                Collections.emptyList(),
                false,
                "",
                -1L,
                message);
    }

    private static Metadata failed(
            final String operation, final RuntimeException error) {
        recordFailure(operation, error);
        return new Metadata(
                Access.FAILED,
                0,
                Collections.emptyList(),
                false,
                "",
                -1L,
                ShellAccess.usefulMessage(error));
    }

    private static OperationResult failure(
            final String operation, final RuntimeException error) {
        recordFailure(operation, error);
        return new OperationResult(
                false, ShellAccess.usefulMessage(error), null);
    }

    private static OperationResult failure(
            final String operation, final String message) {
        synchronized (RUNTIME_LOCK) {
            sFailures++;
            sLastOperation = operation;
            sLastFailure = clean(message);
        }
        return new OperationResult(false, message, null);
    }

    private static void recordFailure(
            final String operation, final RuntimeException error) {
        recordFailure(operation, ShellAccess.usefulMessage(error));
    }

    private static void recordFailure(
            final String operation, final String message) {
        synchronized (RUNTIME_LOCK) {
            sFailures++;
            sLastOperation = operation;
            sLastFailure = clean(message);
        }
    }

    private static void recordSuccess(
            final String operation,
            final boolean textWrite,
            final boolean uriWrite,
            final boolean clear) {
        synchronized (RUNTIME_LOCK) {
            if (textWrite) {
                sTextWrites++;
            }
            if (uriWrite) {
                sUriWrites++;
            }
            if (clear) {
                sClears++;
            }
            sLastOperation = operation;
        }
    }

    private static String safeLabel(final CharSequence label) {
        return label == null || label.length() == 0
                ? "MagicDesk" : label.toString();
    }

    private static boolean isProcessForeground() {
        final ActivityManager.RunningAppProcessInfo process =
                new ActivityManager.RunningAppProcessInfo();
        ActivityManager.getMyMemoryState(process);
        return process.importance
                == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND;
    }

    private static String safeMimeType(final String mimeType) {
        if (mimeType == null) {
            return "application/octet-stream";
        }
        final String cleaned = mimeType.trim();
        final int separator = cleaned.indexOf('/');
        return separator > 0 && separator < cleaned.length() - 1
                ? cleaned : "application/octet-stream";
    }

    private static String clean(final String value) {
        if (value == null || value.isEmpty()) {
            return "unknown";
        }
        final String singleLine = value.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() <= 200
                ? singleLine : singleLine.substring(0, 200);
    }

    private static final class ClipRead {
        final Metadata metadata;
        final ClipData clip;

        ClipRead(final Metadata metadata, final ClipData clip) {
            this.metadata = metadata;
            this.clip = clip;
        }
    }
}
