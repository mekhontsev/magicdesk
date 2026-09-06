package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/** Reads one complete listing on the caller's worker, without owning UI state. */
final class FileDirectoryReader {
    private static final int PAGE_SIZE = 500;

    static final class Request {
        final String path;
        final boolean showHidden;
        final int sortMode;
        final boolean sortAscending;

        Request(final String path, final boolean showHidden, final int sortMode, final boolean sortAscending) {
            this.path = path;
            this.showHidden = showHidden;
            this.sortMode = sortMode;
            this.sortAscending = sortAscending;
        }
    }

    static final class Listing {
        final String path;
        final List<ShellFileInfo> files;
        final Map<String, DesktopEntry> desktopEntries;

        private Listing(final String path, final List<ShellFileInfo> files,
                final Map<String, DesktopEntry> desktopEntries) {
            this.path = path;
            this.files = List.copyOf(files);
            this.desktopEntries = Map.copyOf(desktopEntries);
        }
    }

    interface Source {
        ShellFilePage page(Request request, int offset, int limit) throws IOException;
        DesktopEntry desktopEntry(ShellFileInfo file);
    }

    private FileDirectoryReader() { }

    static Listing read(final Request request, final BooleanSupplier cancelled) throws IOException {
        return read(request, cancelled, new Source() {
            @Override
            public ShellFilePage page(final Request query, final int offset, final int limit) throws IOException {
                return ShellAccess.listShellDirectory(query.path, offset, limit,
                        query.showHidden, query.sortMode, query.sortAscending);
            }

            @Override
            public DesktopEntry desktopEntry(final ShellFileInfo file) {
                return DesktopEntryFile.read(file);
            }
        });
    }

    static Listing read(final Request request, final BooleanSupplier cancelled, final Source source)
            throws IOException {
        final List<ShellFileInfo> files = new ArrayList<>();
        final Map<String, DesktopEntry> entries = new LinkedHashMap<>();
        int offset = 0;
        String path = null;
        while (true) {
            ContentStreamCopy.checkCancelled(cancelled);
            final ShellFilePage page = source.page(request, offset, PAGE_SIZE);
            ContentStreamCopy.checkCancelled(cancelled);
            if (page == null || page.directoryPath == null || page.directoryPath.isEmpty()) {
                throw new IOException("directory listing returned no path");
            }
            if (path != null && !path.equals(page.directoryPath)) {
                throw new IOException("directory changed between listing pages");
            }
            path = page.directoryPath;
            for (final ShellFileInfo file : page.entries) {
                ContentStreamCopy.checkCancelled(cancelled);
                files.add(file);
                final DesktopEntry entry = source.desktopEntry(file);
                if (entry != null) {
                    entries.put(file.absolutePath, entry);
                }
            }
            ContentStreamCopy.checkCancelled(cancelled);
            if (page.complete) {
                return new Listing(path, files, entries);
            }
            if (page.nextOffset <= offset) {
                throw new IOException("directory listing did not advance");
            }
            offset = page.nextOffset;
        }
    }
}
