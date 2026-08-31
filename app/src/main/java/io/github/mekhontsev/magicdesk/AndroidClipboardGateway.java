package io.github.mekhontsev.magicdesk;

import android.app.ActivityManager;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.PersistableBundle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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

    static final class TextReadResult {
        final Metadata metadata;
        final String text;

        TextReadResult(final Metadata metadata, final String text) {
            this.metadata = metadata;
            this.text = text == null ? "" : text;
        }
    }

    static final class ContentReadResult {
        final Metadata metadata;
        final AndroidContentPayload content;

        ContentReadResult(
                final Metadata metadata,
                final AndroidContentPayload content) {
            this.metadata = metadata;
            this.content = content;
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
        return count > 0 && count <= AndroidContentPayload.MAX_URI_ITEMS;
    }

    OperationResult writeText(
            final CharSequence label,
            final CharSequence text,
            final boolean sensitive) {
        if (text == null) {
            return failure("write_text", "text is required");
        }
        return writeContent(
                AndroidContentPayload.text(
                        safeLabel(label),
                        text,
                        sensitive,
                        AndroidContentPayload.Origin.APPLICATION),
                -1L,
                "write_text",
                false);
    }

    OperationResult writeUris(
            final CharSequence label,
            final List<AndroidContentPayload.UriItem> items,
            final long fileGeneration) {
        if (items == null || items.isEmpty()) {
            return failure("write_uris", "at least one URI is required");
        }
        if (!acceptsUriItemCount(items.size())) {
            return failure(
                    "write_uris",
                    "URI item count exceeds "
                            + AndroidContentPayload.MAX_URI_ITEMS);
        }
        for (final AndroidContentPayload.UriItem item : items) {
            if (item == null) {
                return failure("write_uris", "clipboard item is required");
            }
        }
        return writeContent(
                AndroidContentPayload.uris(
                        safeLabel(label),
                        items,
                        Collections.emptyList(),
                        AndroidContentPayload.Origin.APPLICATION),
                fileGeneration,
                "write_uris",
                true);
    }

    TextReadResult readText() {
        final ContentReadResult read = readContent("read_text");
        return new TextReadResult(
                read.metadata,
                read.content == null ? "" : read.content.text);
    }

    ContentReadResult readContent() {
        return readContent("read_content");
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

    private OperationResult writeContent(
            final AndroidContentPayload content,
            final long fileGeneration,
            final String operation,
            final boolean uriWrite) {
        final ClipData clip;
        try {
            clip = content.toClipData();
            if (fileGeneration >= 0L) {
                final ClipDescription description = clip.getDescription();
                final PersistableBundle previous = description.getExtras();
                final PersistableBundle extras = previous == null
                        ? new PersistableBundle()
                        : new PersistableBundle(previous);
                extras.putString(EXTRA_FILE_CLIP_OWNER, FILE_CLIP_OWNER);
                extras.putString(EXTRA_FILE_CLIP_SESSION, PROCESS_SESSION);
                extras.putLong(EXTRA_FILE_CLIP_GENERATION, fileGeneration);
                description.setExtras(extras);
            }
        } catch (RuntimeException error) {
            return failure(operation, error);
        }
        return setPrimaryClip(clip, operation, uriWrite);
    }

    private ContentReadResult readContent(final String operation) {
        final ClipRead read = readPrimaryClip(operation);
        if (read.clip == null) {
            return new ContentReadResult(read.metadata, null);
        }
        try {
            return new ContentReadResult(
                    read.metadata,
                    AndroidContentPayload.fromClipData(
                            read.clip,
                            AndroidContentPayload.Origin.CLIPBOARD));
        } catch (SecurityException error) {
            return new ContentReadResult(denied(operation, error), null);
        } catch (RuntimeException error) {
            return new ContentReadResult(failed(operation, error), null);
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
