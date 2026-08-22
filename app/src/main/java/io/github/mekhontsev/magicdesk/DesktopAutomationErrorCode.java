package io.github.mekhontsev.magicdesk;

/** Stable machine-readable failures returned by desktop automation adapters. */
final class DesktopAutomationErrorCode {
    static final String ACTION_FAILED = "ACTION_FAILED";
    static final String CAPTURE_UNAVAILABLE = "CAPTURE_UNAVAILABLE";
    static final String CONSOLE_ACCESS_FAILED = "CONSOLE_ACCESS_FAILED";
    static final String DESKTOP_NOT_ACTIVE = "DESKTOP_NOT_ACTIVE";
    static final String DISPLAY_NOT_AVAILABLE = "DISPLAY_NOT_AVAILABLE";
    static final String FILE_ACCESS_FAILED = "FILE_ACCESS_FAILED";
    static final String HOST_UNAVAILABLE = "HOST_UNAVAILABLE";
    static final String INVALID_ARGUMENT = "INVALID_ARGUMENT";
    static final String SHELL_UNAVAILABLE = "SHELL_UNAVAILABLE";
    static final String TASK_NOT_FOUND = "TASK_NOT_FOUND";
    static final String TIMEOUT = "TIMEOUT";
    static final String TOOL_DISABLED = "TOOL_DISABLED";
    static final String UNKNOWN_ACTION = "UNKNOWN_ACTION";

    private DesktopAutomationErrorCode() {
    }
}
