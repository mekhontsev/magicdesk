package io.github.mekhontsev.magicdesk;

import java.util.Locale;

final class ShellScriptLauncher {
    private static final String SHELL_MIME_TYPE = "application/x-sh";
    private static final String SHELL_TEXT_MIME_TYPE = "text/x-shellscript";

    private ShellScriptLauncher() {
    }

    static boolean supports(final ShellFileInfo file) {
        if (file == null || file.directory) {
            return false;
        }
        final String name = file.name.toLowerCase(Locale.ROOT);
        return name.endsWith(".sh")
                || SHELL_MIME_TYPE.equals(file.mimeType)
                || SHELL_TEXT_MIME_TYPE.equals(file.mimeType);
    }

    static String command(final ShellFileInfo file) {
        if (!supports(file)) {
            throw new IllegalArgumentException("file is not a shell script");
        }
        final int separator = file.absolutePath.lastIndexOf('/');
        final String parent = separator <= 0
                ? "/" : file.absolutePath.substring(0, separator);
        return "cd -- " + ShellCommandLine.quote(parent)
                + "\n/system/bin/sh -- "
                + ShellCommandLine.quote(file.absolutePath);
    }
}
