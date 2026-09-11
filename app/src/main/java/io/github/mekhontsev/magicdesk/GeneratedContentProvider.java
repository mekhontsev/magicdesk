package io.github.mekhontsev.magicdesk;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/** Read-only URI grants for explicitly exported app-generated content, without privileged storage. */
public final class GeneratedContentProvider extends ContentProvider {
    private static final String SUFFIX = ".generated-content";

    static Uri publish(Context context, String name, GeneratedContentStore.Writer writer) throws IOException {
        String displayName = ShellFileNamePolicy.validate(name);
        String id = GeneratedContentStore.publish(directory(context), writer, System.currentTimeMillis());
        return new Uri.Builder().scheme("content").authority(context.getPackageName() + SUFFIX)
                .appendPath(id).appendPath(displayName).build();
    }

    private static File directory(Context context) { return new File(context.getCacheDir(), "generated-content"); }

    private File resolve(Uri uri) throws FileNotFoundException {
        List<String> path = uri.getPathSegments();
        if (!"content".equals(uri.getScheme()) || getContext() == null
                || !(getContext().getPackageName() + SUFFIX).equals(uri.getAuthority()) || path.size() != 2) {
            throw new FileNotFoundException("invalid content URI");
        }
        try { ShellFileNamePolicy.validate(path.get(1)); }
        catch (IllegalArgumentException error) { throw new FileNotFoundException("invalid content name"); }
        return GeneratedContentStore.resolve(directory(getContext()), path.get(0), System.currentTimeMillis());
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        try { resolve(uri); }
        catch (FileNotFoundException error) { return null; }
        String name = uri.getLastPathSegment();
        int dot = name.lastIndexOf('.');
        String type = dot < 0 ? null : android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT));
        return type == null ? "application/octet-stream" : type;
    }

    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        try {
            File file = resolve(uri);
            Object[] row = new Object[columns.length];
            for (int i = 0; i < columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i] = uri.getLastPathSegment();
                else if (OpenableColumns.SIZE.equals(columns[i])) row[i] = file.length();
            }
            cursor.addRow(row);
        } catch (FileNotFoundException ignored) { /* Expired exports expose no metadata. */ }
        return cursor;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("export is read-only");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException("read-only"); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException("read-only");
    }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException("read-only"); }
}
