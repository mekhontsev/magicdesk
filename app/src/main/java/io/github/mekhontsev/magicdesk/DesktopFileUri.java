package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.net.Uri;
import android.util.Base64;

import java.nio.charset.StandardCharsets;

final class DesktopFileUri {
    private static final String PATH_FILES = "files";

    private DesktopFileUri() {
    }

    static Uri create(final Context context, final String relativePath) {
        return new Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(PATH_FILES)
                .appendPath(encode(relativePath))
                .build();
    }

    static String parse(final Context context, final Uri uri) {
        if (uri == null
                || !"content".equals(uri.getScheme())
                || !authority(context).equals(uri.getAuthority())) {
            throw new IllegalArgumentException("invalid desktop file URI");
        }
        final java.util.List<String> segments = uri.getPathSegments();
        if (segments.size() != 2 || !PATH_FILES.equals(segments.get(0))) {
            throw new IllegalArgumentException("invalid desktop file URI");
        }
        try {
            final String path = new String(
                    Base64.decode(
                            segments.get(1),
                            Base64.URL_SAFE | Base64.NO_WRAP
                                    | Base64.NO_PADDING),
                    StandardCharsets.UTF_8);
            if (path.length() == 0) {
                throw new IllegalArgumentException("empty desktop file path");
            }
            return path;
        } catch (IllegalArgumentException error) {
            throw new IllegalArgumentException(
                    "invalid desktop file URI", error);
        }
    }

    static String authority(final Context context) {
        return context.getPackageName() + ".desktop-files";
    }

    private static String encode(final String value) {
        if (value == null || value.length() == 0) {
            throw new IllegalArgumentException("missing desktop file path");
        }
        return Base64.encodeToString(
                value.getBytes(StandardCharsets.UTF_8),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }
}
