package io.github.mekhontsev.magicdesk;

enum FileManagerLayoutMode {
    LIST,
    GRID;

    static FileManagerLayoutMode fromPreference(final String value) {
        if (GRID.name().equals(value)) {
            return GRID;
        }
        return LIST;
    }
}
