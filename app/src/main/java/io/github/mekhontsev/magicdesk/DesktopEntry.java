package io.github.mekhontsev.magicdesk;

/** Parsed freedesktop Desktop Entry supported by MagicDesk. */
abstract class DesktopEntry {
    final String name;
    final String icon;
    final String exec;

    DesktopEntry(
            final String name,
            final String icon,
            final String exec) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("missing desktop entry name");
        }
        this.name = name.trim();
        this.icon = icon == null ? "" : icon;
        this.exec = exec == null ? "" : exec;
    }
}
