package io.github.mekhontsev.magicdesk;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Binder;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.FileNotFoundException;
import java.io.IOException;

public final class ShellFileProvider extends ContentProvider {
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
            return entry(uri).info.mimeType;
        } catch (RuntimeException error) {
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
            final ShellFileInfo info = entry(uri).info;
            final Object[] row = new Object[columns.length];
            for (int index = 0; index < columns.length; index++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) {
                    row[index] = info.name;
                } else if (OpenableColumns.SIZE.equals(columns[index])) {
                    row[index] = Long.valueOf(info.size);
                }
            }
            cursor.addRow(row);
        } catch (RuntimeException ignored) {
            // Empty cursor means that the process-lifetime grant has expired.
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
        try {
            final ShellFileGrantStore.Entry entry = entry(uri);
            final boolean writeRequested = mode != null
                    && (mode.indexOf('w') >= 0
                            || mode.indexOf('a') >= 0
                            || mode.indexOf('+') >= 0);
            if (writeRequested && !entry.writable) {
                throw new FileNotFoundException("file grant is read-only");
            }
            final long identity = Binder.clearCallingIdentity();
            try {
                return ShellAccess.openVerifiedShellFile(entry.info, mode);
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        } catch (IOException | RuntimeException error) {
            final FileNotFoundException failure = new FileNotFoundException(
                    ShellAccess.usefulMessage(error));
            failure.initCause(error);
            throw failure;
        }
    }

    @Override
    public Uri insert(final Uri uri, final ContentValues values) {
        throw new UnsupportedOperationException("shell file grants are fixed");
    }

    @Override
    public int delete(
            final Uri uri,
            final String selection,
            final String[] selectionArgs) {
        throw new UnsupportedOperationException("shell file grants are fixed");
    }

    @Override
    public int update(
            final Uri uri,
            final ContentValues values,
            final String selection,
            final String[] selectionArgs) {
        throw new UnsupportedOperationException("shell file grants are fixed");
    }

    private ShellFileGrantStore.Entry entry(final Uri uri) {
        if (getContext() == null) {
            throw new IllegalStateException("provider context is unavailable");
        }
        return ShellFileGrantStore.resolve(getContext(), uri);
    }
}
