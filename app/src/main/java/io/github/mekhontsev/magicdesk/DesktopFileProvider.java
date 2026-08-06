package io.github.mekhontsev.magicdesk;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.FileNotFoundException;
import java.io.IOException;

public final class DesktopFileProvider extends ContentProvider {
    private static final String[] DEFAULT_COLUMNS = {
            OpenableColumns.DISPLAY_NAME,
            OpenableColumns.SIZE
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(final Uri uri) {
        try {
            return fileInfo(uri).mimeType;
        } catch (IOException | RuntimeException error) {
            return null;
        }
    }

    @Override
    public Cursor query(
            final Uri uri,
            final String[] projection,
            final String selection,
            final String[] selectionArgs,
            final String sortOrder) {
        final String[] columns = projection == null
                ? DEFAULT_COLUMNS : projection;
        final MatrixCursor cursor = new MatrixCursor(columns, 1);
        try {
            final DesktopFileInfo file = fileInfo(uri);
            final Object[] row = new Object[columns.length];
            for (int index = 0; index < columns.length; index++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) {
                    row[index] = file.name;
                } else if (OpenableColumns.SIZE.equals(columns[index])) {
                    row[index] = Long.valueOf(file.size);
                }
            }
            cursor.addRow(row);
        } catch (IOException | RuntimeException ignored) {
            // An empty cursor accurately represents a missing entry.
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openFile(
            final Uri uri, final String mode) throws FileNotFoundException {
        return openFile(uri, mode, null);
    }

    @Override
    public ParcelFileDescriptor openFile(
            final Uri uri,
            final String mode,
            final CancellationSignal signal) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("desktop files are read-only");
        }
        try {
            return ShellAccess.openDesktopFile(relativePath(uri));
        } catch (IOException | RuntimeException error) {
            final FileNotFoundException failure = new FileNotFoundException(
                    ShellAccess.usefulMessage(error));
            failure.initCause(error);
            throw failure;
        }
    }

    @Override
    public Uri insert(final Uri uri, final ContentValues values) {
        throw new UnsupportedOperationException("desktop files are read-only");
    }

    @Override
    public int delete(
            final Uri uri,
            final String selection,
            final String[] selectionArgs) {
        throw new UnsupportedOperationException("desktop files are read-only");
    }

    @Override
    public int update(
            final Uri uri,
            final ContentValues values,
            final String selection,
            final String[] selectionArgs) {
        throw new UnsupportedOperationException("desktop files are read-only");
    }

    private DesktopFileInfo fileInfo(final Uri uri) throws IOException {
        return ShellAccess.getDesktopFileInfo(relativePath(uri));
    }

    private String relativePath(final Uri uri) {
        if (getContext() == null) {
            throw new IllegalStateException("provider context is unavailable");
        }
        return DesktopFileUri.parse(getContext(), uri);
    }
}
