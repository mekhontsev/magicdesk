package io.github.mekhontsev.magicdesk;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class DesktopFileRepository {
    private static final int THUMBNAIL_SIZE = 192;
    private static final int COPY_BUFFER_SIZE = 32 * 1024;
    private static final String FALLBACK_IMPORT_NAME = "Dropped file";

    private final Context mContext;

    DesktopFileRepository(final Context context) {
        mContext = context.getApplicationContext();
    }

    List<DesktopFile> load(final int thumbnailLimit) throws IOException {
        final DesktopFileInfo[] records = ShellAccess.listDesktopFiles();
        Arrays.sort(records, new Comparator<DesktopFileInfo>() {
            @Override
            public int compare(
                    final DesktopFileInfo left,
                    final DesktopFileInfo right) {
                if (left.directory != right.directory) {
                    return left.directory ? -1 : 1;
                }
                return left.name.compareToIgnoreCase(right.name);
            }
        });
        final List<DesktopFile> files = new ArrayList<>(records.length);
        int previewsRemaining = Math.max(0, thumbnailLimit);
        for (final DesktopFileInfo record : records) {
            Bitmap thumbnail = null;
            if (!record.directory
                    && previewsRemaining > 0
                    && record.mimeType.startsWith("image/")) {
                thumbnail = loadImageThumbnail(record.relativePath);
                previewsRemaining--;
            }
            files.add(new DesktopFile(
                    record.relativePath,
                    DesktopFileUri.create(mContext, record.relativePath),
                    record.name,
                    record.mimeType,
                    record.modified,
                    record.size,
                    record.directory,
                    thumbnail));
        }
        return files;
    }

    ImportResult importFiles(final List<Uri> uris) throws IOException {
        final Set<String> occupiedNames = new LinkedHashSet<>();
        for (final DesktopFileInfo file : ShellAccess.listDesktopFiles()) {
            occupiedNames.add(file.name);
        }
        int copied = 0;
        Throwable firstFailure = null;
        for (final Uri uri : uris) {
            String createdPath = null;
            try {
                final String name = uniqueImportName(
                        displayName(uri), occupiedNames);
                final DesktopFileInfo created =
                        ShellAccess.createDesktopEntry(name, false);
                createdPath = created.relativePath;
                copy(uri, created.relativePath);
                occupiedNames.add(created.name);
                copied++;
            } catch (IOException | RuntimeException error) {
                if (firstFailure == null) {
                    firstFailure = error;
                }
                if (createdPath != null) {
                    try {
                        ShellAccess.deleteDesktopEntry(createdPath);
                    } catch (IOException | RuntimeException cleanupError) {
                        error.addSuppressed(cleanupError);
                    }
                }
            }
        }
        return new ImportResult(copied, uris.size() - copied, firstFailure);
    }

    static String uniqueImportName(
            final String requestedName,
            final Set<String> occupiedNames) {
        final String name;
        try {
            name = DesktopPathPolicy.validateName(requestedName);
        } catch (IllegalArgumentException error) {
            return uniqueImportName(FALLBACK_IMPORT_NAME, occupiedNames);
        }
        if (!containsIgnoreCase(occupiedNames, name)) {
            return name;
        }
        final int extensionStart = name.lastIndexOf('.');
        final boolean hasExtension = extensionStart > 0
                && extensionStart < name.length() - 1;
        final String stem = hasExtension
                ? name.substring(0, extensionStart) : name;
        final String extension = hasExtension
                ? name.substring(extensionStart) : "";
        for (int suffix = 2; ; suffix++) {
            final String candidate = stem + " (" + suffix + ")" + extension;
            if (!containsIgnoreCase(occupiedNames, candidate)) {
                return candidate;
            }
        }
    }

    private String displayName(final Uri uri) {
        final ContentResolver resolver = mContext.getContentResolver();
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
        return FALLBACK_IMPORT_NAME;
    }

    private void copy(final Uri source, final String targetPath)
            throws IOException {
        try (InputStream input = mContext.getContentResolver()
                     .openInputStream(source)) {
            if (input == null) {
                throw new IOException("cannot open dropped file");
            }
            try (OutputStream output =
                         new ParcelFileDescriptor.AutoCloseOutputStream(
                                 ShellAccess.openDesktopFile(
                                         targetPath, "w"))) {
                final byte[] buffer = new byte[COPY_BUFFER_SIZE];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
            }
        } catch (RuntimeException error) {
            throw new IOException("cannot read dropped file", error);
        }
    }

    private static boolean containsIgnoreCase(
            final Set<String> names, final String candidate) {
        final String normalized = candidate.toLowerCase(Locale.ROOT);
        for (final String name : names) {
            if (name.toLowerCase(Locale.ROOT).equals(normalized)) {
                return true;
            }
        }
        return false;
    }

    private Bitmap loadImageThumbnail(final String relativePath) {
        final BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (ParcelFileDescriptor descriptor =
                     ShellAccess.openDesktopFile(relativePath)) {
            BitmapFactory.decodeFileDescriptor(
                    descriptor.getFileDescriptor(), null, bounds);
        } catch (IOException | RuntimeException error) {
            return null;
        }
        final int largest = Math.max(bounds.outWidth, bounds.outHeight);
        if (largest <= 0) {
            return null;
        }
        final BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = 1;
        while (largest / (decode.inSampleSize * 2) >= THUMBNAIL_SIZE) {
            decode.inSampleSize *= 2;
        }
        try (ParcelFileDescriptor descriptor =
                     ShellAccess.openDesktopFile(relativePath)) {
            return BitmapFactory.decodeFileDescriptor(
                    descriptor.getFileDescriptor(), null, decode);
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    static final class ImportResult {
        final int copied;
        final int failed;
        final Throwable firstFailure;

        ImportResult(
                final int copied,
                final int failed,
                final Throwable firstFailure) {
            this.copied = copied;
            this.failed = failed;
            this.firstFailure = firstFailure;
        }
    }
}
