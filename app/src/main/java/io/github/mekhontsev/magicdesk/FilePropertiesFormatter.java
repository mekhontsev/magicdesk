package io.github.mekhontsev.magicdesk;

import android.content.Context;

import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;

final class FilePropertiesFormatter {
    private FilePropertiesFormatter() {
    }

    static String format(final Context context, final ShellFileInfo file) {
        final StringBuilder message = new StringBuilder()
                .append(context.getString(R.string.file_manager_path,
                        file.absolutePath)).append('\n')
                .append(context.getString(R.string.file_manager_type,
                        file.symbolicLink ? "symbolic link"
                                : file.directory ? "folder" : file.mimeType))
                .append('\n')
                .append(context.getString(R.string.file_manager_size,
                        FileSizeFormatter.format(file.size))).append('\n')
                .append(context.getString(R.string.file_manager_modified,
                        DateFormat.getDateTimeInstance().format(
                                new Date(file.modified))))
                .append('\n')
                .append(context.getString(R.string.file_manager_permissions,
                        permissions(file))).append('\n')
                .append(context.getString(R.string.file_manager_mode,
                        String.format(Locale.ROOT, "%04o",
                                file.mode & 07777))).append('\n')
                .append(context.getString(R.string.file_manager_owner,
                        file.ownerUid, file.ownerGid)).append('\n')
                .append(context.getString(R.string.file_manager_identity,
                        ShellAccess.currentSnapshot().uid));
        if (file.symbolicLink) {
            message.append('\n').append(context.getString(
                    R.string.file_manager_link_target, file.linkTarget));
        }
        return message.toString();
    }

    private static String permissions(final ShellFileInfo file) {
        return (file.readable ? "r" : "-")
                + (file.writable ? "w" : "-")
                + (file.executable ? "x" : "-");
    }

}
