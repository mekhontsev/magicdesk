package io.github.mekhontsev.magicdesk;

final class DesktopFolderShortcut {
    final String name;
    final String targetPath;
    final boolean available;

    DesktopFolderShortcut(
            final String name,
            final String targetPath,
            final boolean available) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("missing shortcut name");
        }
        if (targetPath == null || targetPath.isEmpty()) {
            throw new IllegalArgumentException("missing shortcut target");
        }
        this.name = name;
        this.targetPath = targetPath;
        this.available = available;
    }
}
