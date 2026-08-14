package io.github.mekhontsev.magicdesk;

import java.util.Locale;

final class ShellPackageInstaller {
    private static final String APK_MIME_TYPE =
            "application/vnd.android.package-archive";

    private ShellPackageInstaller() {
    }

    static boolean supports(final ShellFileInfo file) {
        return file != null
                && supports(file.name, file.mimeType, file.directory);
    }

    static boolean supports(
            final String name,
            final String mimeType,
            final boolean directory) {
        return !directory
                && (APK_MIME_TYPE.equals(mimeType)
                        || name.toLowerCase(Locale.ROOT).endsWith(".apk"));
    }

    static String command(final String absolutePath) {
        return "/system/bin/pm install -r "
                + ShellCommandLine.quote(absolutePath);
    }
}
