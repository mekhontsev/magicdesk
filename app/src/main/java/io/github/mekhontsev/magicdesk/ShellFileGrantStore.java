package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.net.Uri;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

final class ShellFileGrantStore {
    private static final String PATH_GRANTS = "grants";
    private static final Map<String, Entry> ENTRIES =
            new LinkedHashMap<>();

    private ShellFileGrantStore() {
    }

    static synchronized Uri create(
            final Context context,
            final ShellFileInfo info,
            final boolean writable) {
        if (info == null || info.directory) {
            throw new IllegalArgumentException("only files can be shared");
        }
        final String token = UUID.randomUUID().toString();
        ENTRIES.put(token, new Entry(info, writable));
        return new Uri.Builder()
                .scheme("content")
                .authority(authority(context))
                .appendPath(PATH_GRANTS)
                .appendPath(token)
                .build();
    }

    static synchronized Entry resolve(
            final Context context, final Uri uri) {
        if (uri == null
                || !"content".equals(uri.getScheme())
                || !authority(context).equals(uri.getAuthority())) {
            throw new IllegalArgumentException("invalid shell file URI");
        }
        final java.util.List<String> segments = uri.getPathSegments();
        if (segments.size() != 2
                || !PATH_GRANTS.equals(segments.get(0))) {
            throw new IllegalArgumentException("invalid shell file URI");
        }
        final Entry entry = ENTRIES.get(segments.get(1));
        if (entry == null) {
            throw new IllegalArgumentException("expired shell file URI");
        }
        return entry;
    }

    static String authority(final Context context) {
        return context.getPackageName() + ".shell-files";
    }

    static final class Entry {
        final ShellFileInfo info;
        final boolean writable;

        Entry(
                final ShellFileInfo info,
                final boolean writable) {
            this.info = info;
            this.writable = writable;
        }
    }
}
