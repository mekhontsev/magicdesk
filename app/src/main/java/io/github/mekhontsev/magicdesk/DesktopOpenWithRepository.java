package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Finds executable Desktop Entries that explicitly handle a MIME type. */
final class DesktopOpenWithRepository {
    private static final int MAX_CANDIDATES = 256;

    private DesktopOpenWithRepository() {
    }

    static List<Handler> query(final String mimeType) throws IOException {
        final List<Handler> handlers = new ArrayList<>();
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
            if (!(entry instanceof DesktopApplicationShortcut)) {
                continue;
            }
            final DesktopApplicationShortcut shortcut =
                    (DesktopApplicationShortcut) entry;
            if (accepts(shortcut, mimeType)) {
                handlers.add(new Handler(
                        shortcut,
                        ShellDesktopDirectory.ABSOLUTE_PATH
                                + "/" + file.relativePath));
            }
        }
        handlers.sort(Comparator
                .comparing(
                        (Handler handler) -> handler.shortcut.name,
                        String.CASE_INSENSITIVE_ORDER)
                .thenComparing(handler -> handler.desktopFilePath));
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

    static final class Handler {
        final DesktopApplicationShortcut shortcut;
        final String desktopFilePath;

        Handler(
                final DesktopApplicationShortcut shortcut,
                final String desktopFilePath) {
            this.shortcut = shortcut;
            this.desktopFilePath = desktopFilePath;
        }
    }
}
