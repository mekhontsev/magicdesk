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
import java.util.List;
import java.util.function.BooleanSupplier;

/** Shared provider-to-shell transfer used by Desktop and Files imports. */
final class ContentUriTransfer {
    static final String FALLBACK_FILE_NAME = "Imported file";

    private ContentUriTransfer() {
    }

    static ContentImportBatch<Uri> prepareUris(
            final ContentResolver resolver, final List<Uri> uris, final String destination) {
        ShellFilePathPolicy.absolute(destination);
        return new ContentImportBatch<>(uris,
                (uri, cancelled) -> importUri(resolver, uri, destination, cancelled));
    }

    static ContentImportBatch<?> prepareContent(
            final ContentResolver resolver, final AndroidContentPayload content,
            final String destination) {
        if (content != null && content.hasUris()) {
            return prepareUris(resolver, content.uris(), destination);
        }
        ShellFilePathPolicy.absolute(destination);
        return new ContentImportBatch<>(content == null || content.isEmpty()
                ? List.of() : List.of(content),
                (item, cancelled) -> importTextToShellDirectory(destination, item, cancelled));
    }

    private static String displayName(
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

    private static ShellFileInfo importUri(
            final ContentResolver resolver,
            final Uri source,
            final String destination,
            final BooleanSupplier cancelled) throws IOException {
        ContentStreamCopy.checkCancelled(cancelled);
        final String name = safeFileName(displayName(resolver, source, FALLBACK_FILE_NAME));
        ContentStreamCopy.checkCancelled(cancelled);
        try (ShellFileCreation target = ShellAccess.beginShellFileCreation(destination, name)) {
            ContentStreamCopy.checkCancelled(cancelled);
            // Both streams must close before committing; a provider close error
            // still belongs to this transfer and must leave rollback armed.
            try (InputStream input = resolver.openInputStream(source)) {
                if (input == null) {
                    throw new IOException("source provider returned no data");
                }
                ContentStreamCopy.checkCancelled(cancelled);
                try (OutputStream output = new ParcelFileDescriptor
                        .AutoCloseOutputStream(target.open())) {
                    ContentStreamCopy.copy(input, output, cancelled);
                }
            }
            ContentStreamCopy.checkCancelled(cancelled);
            target.commit();
            return target.file;
        } catch (RuntimeException error) {
            throw new IOException("cannot read imported file", error);
        }
    }

    private static ShellFileInfo importTextToShellDirectory(
            final String destination,
            final AndroidContentPayload content,
            final BooleanSupplier cancelled) throws IOException {
        if (content == null || !content.hasText() || content.hasUris()) {
            throw new IllegalArgumentException(
                    "plain clipboard text is required");
        }
        final boolean htmlOnly = content.text.isEmpty()
                && !content.htmlText.isEmpty();
        final String requestedName = textFileName(
                content.subject, content.label, htmlOnly);
        ContentStreamCopy.checkCancelled(cancelled);
        try (ShellFileCreation target = ShellAccess.beginShellFileCreation(
                destination, requestedName)) {
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new ParcelFileDescriptor.AutoCloseOutputStream(target.open()),
                    StandardCharsets.UTF_8)) {
                ContentStreamCopy.checkCancelled(cancelled);
                writer.write(htmlOnly ? content.htmlText : content.text);
            }
            ContentStreamCopy.checkCancelled(cancelled);
            target.commit();
            return target.file;
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

    static String safeFileName(final String requested) {
        try {
            return ShellFileNamePolicy.validate(requested);
        } catch (IllegalArgumentException error) {
            return FALLBACK_FILE_NAME;
        }
    }
}
