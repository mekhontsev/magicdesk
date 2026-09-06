package io.github.mekhontsev.magicdesk;

/** Shared Desktop Entry directory validation and one-shot shell preparation. */
final class DesktopExecWorkingDirectory {
    private static final int MAX_LENGTH = 4096;

    private DesktopExecWorkingDirectory() {
    }

    static String shellCommand(final String command, final String workingDirectory) {
        final String directory = normalize(workingDirectory);
        if (directory.isEmpty()) {
            return command;
        }
        // A failed cd must stop the whole script, including lists and user error branches.
        return "cd -- " + ShellCommandLine.quote(directory) + " || exit\n" + command;
    }

    static String normalize(final String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        if (value.length() > MAX_LENGTH
                || value.indexOf('\0') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0
                || value.charAt(0) != '/') {
            throw new IllegalArgumentException(
                    "invalid desktop Exec working directory");
        }
        return value;
    }
}
