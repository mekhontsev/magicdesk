package io.github.mekhontsev.magicdesk;

import android.graphics.Bitmap;
import android.net.Uri;

final class DesktopFile {
    final String relativePath;
    final Uri uri;
    final String name;
    final String mimeType;
    final long modified;
    final long size;
    final boolean directory;
    final Bitmap thumbnail;
    final DesktopFolderShortcut folderShortcut;

    DesktopFile(
            final String relativePath,
            final Uri uri,
            final String name,
            final String mimeType,
            final long modified,
            final long size,
            final boolean directory,
            final Bitmap thumbnail,
            final DesktopFolderShortcut folderShortcut) {
        this.relativePath = relativePath;
        this.uri = uri;
        this.name = name;
        this.mimeType = mimeType;
        this.modified = modified;
        this.size = size;
        this.directory = directory;
        this.thumbnail = thumbnail;
        this.folderShortcut = folderShortcut;
    }

    String displayName() {
        return folderShortcut == null ? name : folderShortcut.name;
    }

    boolean opensDirectory() {
        return directory || folderShortcut != null;
    }
}
