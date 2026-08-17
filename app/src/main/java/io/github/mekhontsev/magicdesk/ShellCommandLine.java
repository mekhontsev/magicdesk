package io.github.mekhontsev.magicdesk;

public final class ShellCommandLine {
    private ShellCommandLine() {
    }

    public static String quote(final String value) {
        if (value == null) {
            throw new IllegalArgumentException("missing shell argument");
        }
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
