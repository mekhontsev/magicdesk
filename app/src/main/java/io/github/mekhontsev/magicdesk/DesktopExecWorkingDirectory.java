package io.github.mekhontsev.magicdesk;

/** Validation shared by shell and Termux Desktop Entry working directories. */
final class DesktopExecWorkingDirectory {
    private static final int MAX_LENGTH = 4096;

    private DesktopExecWorkingDirectory() {
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
