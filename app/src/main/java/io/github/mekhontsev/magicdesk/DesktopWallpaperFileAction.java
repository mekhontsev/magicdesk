package io.github.mekhontsev.magicdesk;

import android.graphics.BitmapFactory;
import android.os.ParcelFileDescriptor;

import java.io.IOException;

final class DesktopWallpaperFileAction {
    private DesktopWallpaperFileAction() {
    }

    static boolean supports(final ShellFileInfo file) {
        return file != null
                && supports(file.mimeType, file.directory);
    }

    static boolean supports(
            final String mimeType, final boolean directory) {
        return !directory
                && mimeType != null
                && mimeType.startsWith("image/");
    }

    static void apply(final ShellFileInfo file) throws IOException {
        if (!supports(file)) {
            throw new IOException("selected file is not an image");
        }
        try (ParcelFileDescriptor source =
                ShellAccess.openVerifiedShellFile(file, "r")) {
            final BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFileDescriptor(
                    source.getFileDescriptor(), null, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw new IOException(
                        "selected file is not a decodable image");
            }
        }
        try (ParcelFileDescriptor source =
                ShellAccess.openVerifiedShellFile(file, "r")) {
            ShellAccess.writeDesktopWallpaper(source);
        }
    }
}
