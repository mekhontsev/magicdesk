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

    static String semanticWindowingMode(final String nativeMode) {
        if ("freeform".equalsIgnoreCase(nativeMode)) {
            return WINDOWED.wireName;
        }
        if ("fullscreen".equalsIgnoreCase(nativeMode)) {
            return FULLSCREEN.wireName;
        }
        return nativeMode == null ? "" : nativeMode;
    }

    static boolean matchesWindowingMode(
            final String requestedMode,
            final String nativeMode) {
        return requestedMode != null
                && (requestedMode.equalsIgnoreCase(nativeMode)
                        || requestedMode.equalsIgnoreCase(
                                semanticWindowingMode(nativeMode)));
    }
}
