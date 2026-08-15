package io.github.mekhontsev.magicdesk;

import java.util.Locale;

final class ShellScriptLauncher {
    private static final String SHELL_MIME_TYPE = "application/x-sh";
    private static final String SHELL_TEXT_MIME_TYPE = "text/x-shellscript";

    private ShellScriptLauncher() {
    }

    static boolean supports(final ShellFileInfo file) {
        return file != null
                && supports(file.name, file.mimeType, file.directory);
    }

    static boolean supports(
            final String fileName,
            final String mimeType,
            final boolean directory) {
        if (directory) {
            return false;
        }
        final String name = fileName.toLowerCase(Locale.ROOT);
        return name.endsWith(".sh")
                || SHELL_MIME_TYPE.equals(mimeType)
                || SHELL_TEXT_MIME_TYPE.equals(mimeType);
    }

    static String command(final ShellFileInfo file) {
        if (!supports(file)) {
            throw new IllegalArgumentException("file is not a shell script");
        }
        return command(file.absolutePath);
    }

    static String command(final String absolutePath) {
        return "/system/bin/sh -- " + ShellCommandLine.quote(absolutePath);
    }

    static String workingDirectory(final String absolutePath) {
        final int separator = absolutePath.lastIndexOf('/');
        return separator <= 0 ? "/" : absolutePath.substring(0, separator);
    }
}
