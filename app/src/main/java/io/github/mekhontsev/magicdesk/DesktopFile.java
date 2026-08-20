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
    final DesktopEntry desktopEntry;

    DesktopFile(
            final String relativePath,
            final Uri uri,
            final String name,
            final String mimeType,
            final long modified,
            final long size,
            final boolean directory,
            final Bitmap thumbnail,
            final DesktopEntry desktopEntry) {
        this.relativePath = relativePath;
        this.uri = uri;
        this.name = name;
        this.mimeType = mimeType;
        this.modified = modified;
        this.size = size;
        this.directory = directory;
        this.thumbnail = thumbnail;
        this.desktopEntry = desktopEntry;
    }

    String displayName() {
        return desktopEntry == null ? name : desktopEntry.name;
    }

    boolean opensDirectory() {
        return directory || folderShortcut() != null;
    }

    DesktopFolderShortcut folderShortcut() {
        return desktopEntry instanceof DesktopFolderShortcut
                ? (DesktopFolderShortcut) desktopEntry : null;
    }

    DesktopApplicationShortcut applicationShortcut() {
        return desktopEntry instanceof DesktopApplicationShortcut
                ? (DesktopApplicationShortcut) desktopEntry : null;
    }

    DesktopWebShortcut webShortcut() {
        return desktopEntry instanceof DesktopWebShortcut
                ? (DesktopWebShortcut) desktopEntry : null;
    }
}
