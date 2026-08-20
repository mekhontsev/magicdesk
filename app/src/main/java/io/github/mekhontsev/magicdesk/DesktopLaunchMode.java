package io.github.mekhontsev.magicdesk;

enum DesktopLaunchMode {
    AUTO("auto"),
    WINDOWED("windowed"),
    FULLSCREEN("fullscreen");

    final String wireName;

    DesktopLaunchMode(final String wireName) {
        this.wireName = wireName;
    }

    static DesktopLaunchMode parse(final String value) {
        if (value != null) {
            for (final DesktopLaunchMode mode : values()) {
                if (mode.wireName.equalsIgnoreCase(value.trim())) {
                    return mode;
                }
            }
        }
        return AUTO;
    }
}
