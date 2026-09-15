package io.github.mekhontsev.magicdesk;

/** Execution environment selected by a Desktop Entry. */
enum DesktopExecBackend {
    SHELL("shell", new DesktopExecCapabilities(
            true, true, true, true)),
    TERMUX("termux", new DesktopExecCapabilities(
            true, true, true, false)),
    X11("x11", new DesktopExecCapabilities(
            true, true, true, false));

    final String wireName;
    private final DesktopExecCapabilities capabilities;

    DesktopExecBackend(
            final String wireName,
            final DesktopExecCapabilities capabilities) {
        this.wireName = wireName;
        this.capabilities = capabilities;
    }

    DesktopExecCapabilities capabilities() {
        return capabilities;
    }

    DesktopExecBackend requireConsole() {
        if (this == X11) throw new IllegalArgumentException("X11 commands require an X11 application window");
        return this;
    }

    static DesktopExecBackend parse(final String value) {
        if (value == null || value.trim().isEmpty()) {
            return SHELL;
        }
        for (final DesktopExecBackend backend : values()) {
            if (backend.wireName.equalsIgnoreCase(value.trim())) {
                return backend;
            }
        }
        throw new IllegalArgumentException("unknown desktop Exec backend");
    }
}
