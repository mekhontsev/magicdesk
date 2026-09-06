package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.net.Uri;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ShellFileGrantStore {
    private static final String PATH_GRANTS = "grants";
    private static final int MAX_GRANTS = 256;
    private static final Map<String, Entry> ENTRIES =
            new LinkedHashMap<String, Entry>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        final Map.Entry<String, ShellFileGrantStore.Entry>
                                eldest) {
                    return size() > MAX_GRANTS;
                }
            };

    private ShellFileGrantStore() {
    }

    static Uri create(
            final Context context,
            final ShellFileInfo info,
            final boolean writable) {
        final Entry entry = new Entry(info, writable);
        final Preparation prepared = new Preparation(context);
        final Uri uri = prepared.add(entry);
        prepared.publish();
        return uri;
    }

    static boolean canShareReadOnly(final List<ShellFileInfo> files) {
        if (files == null || files.isEmpty()
                || files.size() > AndroidContentPayload.MAX_URI_ITEMS) {
            return false;
        }
        for (final ShellFileInfo file : files) {
            if (file == null || !file.isRegularFile()
                    || file.directory || file.symbolicLink || !file.readable) {
                return false;
            }
        }
        return true;
    }

    static List<AndroidContentPayload.UriItem> createReadOnlySelection(
            final Context context, final List<ShellFileInfo> files) {
        if (!canShareReadOnly(files)) {
            return List.of();
        }
        final Preparation prepared = new Preparation(context);
        final List<AndroidContentPayload.UriItem> items = new ArrayList<>(files.size());
        for (final ShellFileInfo file : files) {
            items.add(new AndroidContentPayload.UriItem(
                    prepared.add(new Entry(file, false)), file.mimeType));
        }
        final List<AndroidContentPayload.UriItem> selection = List.copyOf(items);
        prepared.publish();
        return selection;
    }

    static synchronized void discardUnpublished(
            final Context context, final List<AndroidContentPayload.UriItem> items) {
        // Only failed publication is discarded; accepted consumers may still be reading.
        for (final AndroidContentPayload.UriItem item : items) {
            ENTRIES.remove(token(context, item.uri));
        }
    }

    private static Uri uri(final String authority, final String token) {
        return new Uri.Builder()
                .scheme("content")
                .authority(authority)
                .appendPath(PATH_GRANTS)
                .appendPath(token)
                .build();
    }

    static synchronized Entry resolve(
            final Context context, final Uri uri) {
        final Entry entry = ENTRIES.get(token(context, uri));
        if (entry == null) {
            throw new IllegalArgumentException("expired shell file URI");
        }
        return entry;
    }

    private static String token(final Context context, final Uri uri) {
        if (uri == null
                || !"content".equals(uri.getScheme())
                || !authority(context).equals(uri.getAuthority())) {
            throw new IllegalArgumentException("invalid shell file URI");
        }
        final List<String> segments = uri.getPathSegments();
        if (segments.size() != 2
                || !PATH_GRANTS.equals(segments.get(0))) {
            throw new IllegalArgumentException("invalid shell file URI");
        }
        return segments.get(1);
    }

    static String authority(final Context context) {
        return context.getPackageName() + ".shell-files";
    }

    /** Request-local URI preparation; the provider cannot resolve these until publication. */
    static final class Preparation {
        private final String mAuthority;
        private final Map<String, Entry> mEntries = new LinkedHashMap<>();
        private boolean mPublished;

        Preparation(final Context context) {
            mAuthority = authority(context);
        }

        Uri add(final Entry entry) {
            requireUnpublished();
            if (entry == null || mEntries.size() >= AndroidContentPayload.MAX_URI_ITEMS) {
                throw new IllegalArgumentException("invalid file grant selection");
            }
            final String token = UUID.randomUUID().toString();
            final Uri uri = uri(mAuthority, token);
            mEntries.put(token, entry);
            return uri;
        }

        void publish() {
            requireUnpublished();
            // Validate and build the complete request before allowing it to evict older grants.
            synchronized (ShellFileGrantStore.class) {
                ENTRIES.putAll(mEntries);
            }
            mPublished = true;
            mEntries.clear();
        }

        private void requireUnpublished() {
            if (mPublished) {
                throw new IllegalStateException("file grants already published");
            }
        }
    }

    static final class Entry {
        final ShellFileInfo info;
        final boolean writable;

        Entry(
                final ShellFileInfo info,
                final boolean writable) {
            if (info == null || info.directory || !info.isRegularFile()) {
                throw new IllegalArgumentException("only regular files can be shared");
            }
            this.info = info;
            this.writable = writable && info.writable;
        }
    }
}
