package io.github.mekhontsev.magicdesk;

/** Execution environment selected by a Desktop Entry. */
enum DesktopExecBackend {
    SHELL("shell"),
    TERMUX("termux");

    final String wireName;

    DesktopExecBackend(final String wireName) {
        this.wireName = wireName;
    }

    DesktopExecCapabilities capabilities() {
        if (this == TERMUX) {
            return new DesktopExecCapabilities(
                    true,
                    true,
                    true,
                    false,
                    TermuxIntegration.PACKAGE_NAME);
        }
        return new DesktopExecCapabilities(
                true,
                true,
                true,
                true,
                "");
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
