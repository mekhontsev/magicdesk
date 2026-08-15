package io.github.mekhontsev.magicdesk;

final class FileIconResolver {
    private FileIconResolver() {
    }

    static int forMimeType(final String mimeType) {
        if (mimeType != null && mimeType.startsWith("image/")) {
            return R.drawable.ic_desktop_file_image;
        }
        if (mimeType != null
                && (mimeType.startsWith("audio/")
                        || mimeType.startsWith("video/"))) {
            return R.drawable.ic_desktop_file_media;
        }
        if ("application/pdf".equals(mimeType)) {
            return R.drawable.ic_desktop_file_pdf;
        }
        if (mimeType != null
                && (mimeType.startsWith("text/")
                        || mimeType.contains("json")
                        || mimeType.contains("xml"))) {
            return R.drawable.ic_desktop_file_text;
        }
        if (mimeType != null
                && (mimeType.contains("zip")
                        || mimeType.contains("archive")
                        || mimeType.contains("compressed"))) {
            return R.drawable.ic_desktop_file_archive;
        }
        return R.drawable.ic_desktop_file_document;
    }

    static int forFile(
            final boolean directory,
            final String mimeType) {
        return directory
                ? R.drawable.ic_desktop_folder
                : forMimeType(mimeType);
    }
}
