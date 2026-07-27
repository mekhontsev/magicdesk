package io.github.mekhontsev.magicdesk;

import android.net.Uri;

final class DesktopFile {
    final Uri uri;
    final String name;
    final String mimeType;
    final long modified;
    final boolean directory;

    DesktopFile(
            final Uri uri,
            final String name,
            final String mimeType,
            final long modified,
            final boolean directory) {
        this.uri = uri;
        this.name = name;
        this.mimeType = mimeType;
        this.modified = modified;
        this.directory = directory;
    }
}
