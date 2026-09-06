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
        this.name = requireName(name);
        this.icon = icon == null ? "" : icon;
        this.exec = exec == null ? "" : exec;
    }

    static String requireName(final String value) {
        if (value == null || value.trim().isEmpty() || value.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid desktop entry name");
        }
        return value.trim();
    }
}
