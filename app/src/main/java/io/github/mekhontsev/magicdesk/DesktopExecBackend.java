package io.github.mekhontsev.magicdesk;

/** Execution environment selected by a Desktop Entry. */
enum DesktopExecBackend {
    SHELL("shell", new DesktopExecCapabilities(
            true, true, true, true)),
    TERMUX("termux", new DesktopExecCapabilities(
            true, true, true, false)),
    APP("app", new DesktopExecCapabilities(true, true, true, true));

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

    RuntimeCapabilities.Service service() {
        return switch (this) {
            case APP -> RuntimeCapabilities.Service.BUILTIN_UI;
            case SHELL -> RuntimeCapabilities.Service.SHELL;
            case TERMUX -> RuntimeCapabilities.Service.TERMUX;
        };
    }

    String home(android.content.Context context) {
        return switch (this) {
            case APP -> context.getDataDir().toString();
            case TERMUX -> TermuxIntegration.homeDirectory(context);
            case SHELL -> RuntimeDataPaths.privileged(ShellAccess.isReady() ? ShellAccess.currentSnapshot().uid : 2000).root().toString();
        };
    }

    int title() {
        return switch (this) {
            case APP -> R.string.console_app_title;
            case SHELL -> R.string.executor_android_shell;
            case TERMUX -> R.string.console_termux_title;
        };
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
