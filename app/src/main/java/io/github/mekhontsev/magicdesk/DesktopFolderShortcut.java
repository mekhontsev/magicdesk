package io.github.mekhontsev.magicdesk;

final class DesktopFolderShortcut extends DesktopEntry {
    final String targetPath;
    final boolean available;

    DesktopFolderShortcut(
            final String name,
            final String targetPath,
            final boolean available) {
        super(name, "folder", "");
        if (targetPath == null || targetPath.isEmpty()) {
            throw new IllegalArgumentException("missing shortcut target");
        }
        this.targetPath = targetPath;
        this.available = available;
    }
}
