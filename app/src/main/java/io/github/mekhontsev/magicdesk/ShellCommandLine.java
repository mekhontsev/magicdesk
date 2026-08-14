package io.github.mekhontsev.magicdesk;

final class ShellCommandLine {
    private ShellCommandLine() {
    }

    static String quote(final String value) {
        if (value == null) {
            throw new IllegalArgumentException("missing shell argument");
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
