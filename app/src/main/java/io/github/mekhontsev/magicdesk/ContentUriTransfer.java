package io.github.mekhontsev.magicdesk;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;

/** Shared provider-to-shell transfer used by Desktop and Files imports. */
final class ContentUriTransfer {
    static final String FALLBACK_FILE_NAME = "Imported file";
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private ContentUriTransfer() {
    }

    static String displayName(
            final ContentResolver resolver,
            final Uri uri,
            final String fallback) {
        try (Cursor cursor = resolver.query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null)) {
            if (cursor != null && cursor.moveToFirst()) {
                final int column = cursor.getColumnIndex(
                        OpenableColumns.DISPLAY_NAME);
                if (column >= 0 && !cursor.isNull(column)) {
                    return cursor.getString(column);
                }
            }
        } catch (RuntimeException ignored) {
            // Providers are not required to expose OpenableColumns.
        }
        return fallback;
    }

    static void copyToShellFile(
            final ContentResolver resolver,
            final Uri source,
            final ShellFileInfo target,
            final BooleanSupplier cancelled) throws IOException {
        try (InputStream input = resolver.openInputStream(source)) {
            if (input == null) {
                throw new IOException("source provider returned no data");
            }
            try (OutputStream output = new ParcelFileDescriptor
                    .AutoCloseOutputStream(
                            ShellAccess.openVerifiedShellFile(target, "w"))) {
                final byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (cancelled != null && cancelled.getAsBoolean()) {
                        throw new IOException("import cancelled");
                    }
                    output.write(buffer, 0, count);
                }
            }
        } catch (RuntimeException error) {
            throw new IOException("cannot read imported file", error);
        }
    }

    static ShellFileInfo importTextToShellDirectory(
            final String destination,
            final AndroidContentPayload content) throws IOException {
        if (content == null || !content.hasText() || content.hasUris()) {
            throw new IllegalArgumentException(
                    "plain clipboard text is required");
        }
        final boolean htmlOnly = content.text.isEmpty()
                && !content.htmlText.isEmpty();
        final String requestedName = textFileName(
                content.subject, content.label, htmlOnly);
        final ShellFileInfo created = ShellAccess.createAvailableShellEntry(
                destination, requestedName, false);
        try {
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new ParcelFileDescriptor.AutoCloseOutputStream(
                            ShellAccess.openVerifiedShellFile(created, "w")),
                    StandardCharsets.UTF_8)) {
                writer.write(htmlOnly ? content.htmlText : content.text);
            }
        } catch (IOException | RuntimeException error) {
            cleanupFailedShellFile(created.absolutePath, error);
            throw error;
        }
        return created;
    }

    static void cleanupFailedShellFile(
            final String path,
            final Throwable original) {
        try {
            ShellAccess.startShellFileOperation(
                    ShellFileSystem.OPERATION_DELETE,
                    new String[]{path},
                    "",
                    new IFileOperationCallback.Stub() {
                        @Override
                        public void onProgress(
                                final long id,
                                final int completed,
                                final int total,
                                final String current,
                                final long bytes) {
                        }

                        @Override
                        public void onFinished(
                                final long id,
                                final boolean successful,
                                final String message) {
                        }
                    },
                    new android.os.Binder());
        } catch (IOException | RuntimeException cleanupError) {
            original.addSuppressed(cleanupError);
        }
    }

    static String textFileName(
            final String subject,
            final String label,
            final boolean html) {
        String name = subject == null || subject.isEmpty()
                ? label : subject;
        if (name == null) {
            name = "";
        }
        if (name.isEmpty() || "MagicDesk".equals(name)) {
            name = "Clipboard text";
        }
        name = name.replace('\n', ' ').replace('\r', ' ').trim();
        final String extension = html ? ".html" : ".txt";
        if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(extension)) {
            name += extension;
        }
        try {
            return ShellFileNamePolicy.validate(name);
        } catch (IllegalArgumentException error) {
            return "Clipboard text" + extension;
        }
    }
}
