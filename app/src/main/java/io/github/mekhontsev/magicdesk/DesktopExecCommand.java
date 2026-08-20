package io.github.mekhontsev.magicdesk;

/** Bounded command handling shared by current and future Exec backends. */
final class DesktopExecCommand {
    static final int MAX_LENGTH = 4096;

    private DesktopExecCommand() {
    }

    static String normalize(final String command) {
        if (command == null) {
            return "";
        }
        final String normalized = command.trim();
        if (normalized.length() > MAX_LENGTH
                || normalized.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid desktop Exec command");
        }
        return normalized;
    }

    static String prepare(final String command) {
        return DesktopExecTemplate.expand(
                command,
                DesktopLaunchArguments.empty(),
                "",
                "",
                "");
    }
}
