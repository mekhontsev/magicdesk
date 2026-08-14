package io.github.mekhontsev.magicdesk;

final class ShellFileNamePolicy {
    private ShellFileNamePolicy() {
    }

    static String validate(final String requestedName) {
        if (requestedName == null) {
            throw new IllegalArgumentException("missing file name");
        }
        final String name = requestedName.trim();
        if (name.length() == 0
                || ".".equals(name)
                || "..".equals(name)
                || name.indexOf('/') >= 0
                || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("invalid file name");
        }
        return name;
    }
}
