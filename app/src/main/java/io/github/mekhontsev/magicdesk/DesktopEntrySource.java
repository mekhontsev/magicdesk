package io.github.mekhontsev.magicdesk;

import android.content.Context;
import java.io.IOException;
import java.util.List;

/** File authority is explicit; a Termux catalog entry never falls back to the shell filesystem. */
enum DesktopEntrySource {
    DESKTOP("desktop"), TERMUX("termux"), GUEST("guest");

    final String wireName;
    DesktopEntrySource(String wireName) { this.wireName = wireName; }

    static DesktopEntrySource parse(String name) {
        for (var source : values()) if (source.wireName.equals(name)) return source;
        throw new IllegalArgumentException("unknown desktop entry source: " + name);
    }

    List<DesktopApplicationRepository.Entry> load(Context context) throws IOException {
        return switch (this) {
            case TERMUX -> ApplicationCatalog.loadTermux(context);
            case GUEST -> ApplicationCatalog.loadGuest(context);
            case DESKTOP -> DesktopApplicationRepository.load();
        };
    }

    DesktopEntry read(Context context, String path) throws IOException {
        if (this == GUEST) return find(ApplicationCatalog.loadGuest(context, false), path).shortcut;
        if (this != DESKTOP) return find(load(context), path).shortcut;
        DesktopEntry entry = DesktopEntryFile.read(ShellAccess.getShellFileInfo(path));
        if (entry == null) throw new IllegalArgumentException("unsupported or invalid .desktop file");
        return entry;
    }

    static DesktopApplicationRepository.Entry find(List<DesktopApplicationRepository.Entry> entries, String path) {
        return entries.stream().filter(entry -> entry.desktopFilePath.equals(path)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("application is not in the selected catalog: " + path));
    }
}
