package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Discovers Application desktop entries for Start, Open With, and drops. */
final class DesktopApplicationRepository {
    private static final int MAX_CANDIDATES = 256;

    private DesktopApplicationRepository() {
    }

    static List<Entry> fromDesktopFiles(final List<DesktopFile> files) {
        final List<Entry> applications = new ArrayList<>();
        if (files == null) {
            return applications;
        }
        for (final DesktopFile file : files) {
            final DesktopApplicationShortcut shortcut =
                    file.applicationShortcut();
            if (shortcut != null) {
                applications.add(new Entry(
                        shortcut,
                        absolutePath(file.relativePath),
                        file));
            }
        }
        sort(applications);
        return applications;
    }

    static List<Entry> load() throws IOException {
        final List<Entry> applications = new ArrayList<>();
        int candidates = 0;
        for (final DesktopFileInfo file : ShellAccess.listDesktopFiles()) {
            if (file.directory
                    || !file.name.toLowerCase(Locale.ROOT)
                            .endsWith(".desktop")) {
                continue;
            }
            if (++candidates > MAX_CANDIDATES) {
                break;
            }
            final DesktopEntry entry = DesktopEntryFile.read(file);
            if (entry instanceof DesktopApplicationShortcut) {
                applications.add(new Entry(
                        (DesktopApplicationShortcut) entry,
                        absolutePath(file.relativePath),
                        null));
            }
        }
        sort(applications);
        return applications;
    }

    static List<Entry> queryHandlers(final String mimeType)
            throws IOException {
        final List<Entry> handlers = new ArrayList<>();
        for (final Entry application : load()) {
            if (accepts(application.shortcut, mimeType)) {
                handlers.add(application);
            }
        }
        return handlers;
    }

    static boolean accepts(
            final DesktopApplicationShortcut shortcut,
            final String mimeType) {
        return shortcut != null
                && shortcut.hasExecLaunch()
                && DesktopExecTemplate.acceptsArguments(shortcut.exec)
                && shortcut.mimeTypes.matches(mimeType);
    }

    private static String absolutePath(final String relativePath) {
        return ShellDesktopDirectory.ABSOLUTE_PATH + "/" + relativePath;
    }

    private static void sort(final List<Entry> applications) {
        applications.sort(Comparator
                .comparing(
                        (Entry entry) -> entry.shortcut.name,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(entry -> entry.desktopFilePath));
    }

    static final class Entry {
        final DesktopApplicationShortcut shortcut;
        final String desktopFilePath;
        final DesktopFile desktopFile;

        Entry(
                final DesktopApplicationShortcut shortcut,
                final String desktopFilePath,
                final DesktopFile desktopFile) {
            this.shortcut = shortcut;
            this.desktopFilePath = desktopFilePath;
            this.desktopFile = desktopFile;
        }
    }
}
