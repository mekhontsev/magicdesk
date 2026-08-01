package io.github.mekhontsev.magicdesk;

import android.content.ContentResolver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.util.Size;

import java.io.IOException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class DesktopFileRepository {
    private final ContentResolver mContentResolver;
    private final int mMaximumFiles;

    DesktopFileRepository(
            final ContentResolver contentResolver,
            final int maximumFiles) {
        mContentResolver = contentResolver;
        mMaximumFiles = maximumFiles;
    }

    List<DesktopFile> load(final Uri treeUri) {
        final String treeDocumentId =
                DocumentsContract.getTreeDocumentId(treeUri);
        final Uri childrenUri =
                DocumentsContract.buildChildDocumentsUriUsingTree(
                        treeUri, treeDocumentId);
        final String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED
        };
        final List<DesktopFile> files = new ArrayList<>();
        try (Cursor cursor = mContentResolver.query(
                childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                return files;
            }
            while (cursor.moveToNext()) {
                final String documentId = cursor.getString(0);
                final String name = cursor.getString(1);
                final String mimeType = cursor.getString(2);
                final long modified =
                        cursor.isNull(3) ? 0L : cursor.getLong(3);
                if (documentId == null || name == null) {
                    continue;
                }
                final Uri documentUri =
                        DocumentsContract.buildDocumentUriUsingTree(
                                treeUri, documentId);
                final boolean directory =
                        DocumentsContract.Document.MIME_TYPE_DIR.equals(
                                mimeType);
                files.add(new DesktopFile(
                        documentUri,
                        name,
                        mimeType,
                        modified,
                        directory,
                        directory ? null
                                : loadImageThumbnail(documentUri, mimeType)));
            }
        }
        Collections.sort(files, new Comparator<DesktopFile>() {
            @Override
            public int compare(
                    final DesktopFile left, final DesktopFile right) {
                if (left.directory != right.directory) {
                    return left.directory ? -1 : 1;
                }
                if (left.modified != right.modified) {
                    return Long.compare(right.modified, left.modified);
                }
                return left.name.compareToIgnoreCase(right.name);
            }
        });
        if (files.size() > mMaximumFiles) {
            return new ArrayList<>(files.subList(0, mMaximumFiles));
        }
        return files;
    }

    private Bitmap loadImageThumbnail(
            final Uri documentUri,
            final String mimeType) {
        if (mimeType == null || !mimeType.startsWith("image/")) {
            return null;
        }
        try {
            return mContentResolver.loadThumbnail(
                    documentUri, new Size(192, 192), null);
        } catch (IOException | RuntimeException ignored) {
            return null;
        }
    }
}
