package io.github.mekhontsev.magicdesk;

/** Immutable reference to a shortcut published through Android LauncherApps. */
final class AndroidShortcutSpec {
    final AppIdentity application;
    final AppLaunchTarget publisher;
    final String shortcutId;

    AndroidShortcutSpec(
            final AppIdentity application,
            final AppLaunchTarget publisher,
            final String shortcutId) {
        if (publisher == null
                || (application != null && !application.packageName.equals(publisher.packageName))
                || shortcutId == null
                || shortcutId.trim().isEmpty()) {
            throw new IllegalArgumentException("invalid Android shortcut");
        }
        this.application = application;
        this.publisher = publisher;
        this.shortcutId = shortcutId.trim();
    }
}
