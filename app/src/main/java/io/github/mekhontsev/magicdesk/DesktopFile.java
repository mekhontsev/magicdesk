package io.github.mekhontsev.magicdesk;

import android.graphics.Bitmap;
import android.net.Uri;

final class DesktopFile {
    final Uri uri;
    final String name;
    final String mimeType;
    final long modified;
    final boolean directory;
    final Bitmap thumbnail;

    DesktopFile(
            final Uri uri,
            final String name,
            final String mimeType,
            final long modified,
            final boolean directory,
            final Bitmap thumbnail) {
        this.uri = uri;
        this.name = name;
        this.mimeType = mimeType;
        this.modified = modified;
        this.directory = directory;
        this.thumbnail = thumbnail;
    }
}
