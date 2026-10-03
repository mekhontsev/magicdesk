package io.github.mekhontsev.magicdesk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.json.JSONException;

/** Bundled documents use the same validator and publication path as imported themes. */
final class ShellThemes {
    record Entry(String id, int title) { String path() { return "themes/" + id + ".json"; } }
    static final List<Entry> ENTRIES = List.of(
            new Entry("workbench", R.string.appearance_theme_workbench),
            new Entry("material", R.string.appearance_theme_material),
            new Entry("cupertino", R.string.appearance_theme_cupertino),
            new Entry("glass-dock", R.string.appearance_theme_glass_dock),
            new Entry("two-panels", R.string.appearance_theme_two_panels),
            new Entry("contours", R.string.appearance_theme_contours));
    interface Source { InputStream open(String path) throws IOException; }
    private ShellThemes() { }

    static ShellAppearance load(String id, Source source) throws IOException, JSONException {
        Entry entry = ENTRIES.stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown built-in theme: " + id));
        try (var input = source.open(entry.path())) {
            return ShellAppearanceJson.parse(new String(ThemeBundleFiles.read(input,
                    ShellAppearanceJson.MAX_BYTES), StandardCharsets.UTF_8));
        }
    }
}
