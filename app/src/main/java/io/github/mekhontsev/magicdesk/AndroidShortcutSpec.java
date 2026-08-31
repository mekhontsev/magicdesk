package io.github.mekhontsev.magicdesk;

/** Immutable reference to a shortcut published through Android LauncherApps. */
final class AndroidShortcutSpec {
    final AppLaunchTarget publisher;
    final String shortcutId;

    AndroidShortcutSpec(
            final AppLaunchTarget publisher,
            final String shortcutId) {
        if (publisher == null
                || shortcutId == null
                || shortcutId.trim().isEmpty()) {
            throw new IllegalArgumentException("invalid Android shortcut");
        }
        this.publisher = publisher;
        this.shortcutId = shortcutId.trim();
    }
}
