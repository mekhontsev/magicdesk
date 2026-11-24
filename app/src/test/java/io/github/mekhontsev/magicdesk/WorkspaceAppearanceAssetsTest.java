package io.github.mekhontsev.magicdesk;

import org.junit.Test;

public final class WorkspaceAppearanceAssetsTest {
    @Test public void retainedResourceSetsHaveAggregateBoundsAndOnlyMissingAssetsPrepare() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static class Context {}
                static class Bitmap {
                    final int bytes;
                    Bitmap(int bytes) { this.bytes = bytes; }
                    int getAllocationByteCount() { return bytes; }
                }
                static class Typeface {}
                record WallpaperAsset(long retainedBytes) {}
                static class ThemeAssets {
                    static int calls;
                    static ThemeAssets get(Context context) { return new ThemeAssets(); }
                    Prepared prepare(ShellResources resources) throws IOException {
                        calls++;
                        throw new IOException("unprepared asset");
                    }
                    static class Prepared {
                        static final Prepared EMPTY = new Prepared(null, null);
                        final Bitmap image;
                        final Typeface face;
                        WallpaperAsset wallpaper;
                        Prepared(Bitmap image, Typeface face) { this.image = image; this.face = face; }
                        Bitmap icon(String path) { return image; }
                        WallpaperAsset wallpaper() { return wallpaper; }
                        Typeface font() { return face; }
                    }
                }
                static ShellResources resource(int id) {
                    return new ShellResources(Map.of(), String.format("%064x", id),
                        Map.of(ShellResources.Icon.DESKTOP, "icons/desktop.png"), "", "wallpapers/work.png", null, ShellResources.WallpaperFit.COVER);
                }
                public static void verify() throws Exception {
                    var first = resource(1); var second = resource(2);
                    var bitmap = new Bitmap((int) WorkspaceAppearanceAssets.MAX_IMAGE_BYTES);
                    var prepared = new ThemeAssets.Prepared(bitmap, null);
                    var active = Map.of(first, prepared, second, prepared);
                    var shared = WorkspaceAppearanceAssets.prepare(null, List.of(first, second), active, Map.of());
                    check(shared.size() == 2 && ThemeAssets.calls == 0, "cached snapshots must not prepare or double-count shared image");
                    var staged = WorkspaceAppearanceAssets.prepare(null, List.of(first), Map.of(), active);
                    check(staged.get(first) == prepared && ThemeAssets.calls == 0, "UI publication reuses staged assets");
                    boolean exceeded = false;
                    try {
                        WorkspaceAppearanceAssets.prepare(null, List.of(first, second),
                            Map.of(first, prepared, second, new ThemeAssets.Prepared(new Bitmap(4), null)), Map.of());
                    } catch (IllegalArgumentException expected) { exceeded = true; }
                    check(exceeded && active.size() == 2, "aggregate retained images, not each bundle, must be bounded");
                    var movie = new ThemeAssets.Prepared(null, null);
                    movie.wallpaper = new WallpaperAsset(WorkspaceAppearanceAssets.MAX_IMAGE_BYTES);
                    var sharedMovie = WorkspaceAppearanceAssets.prepare(null, List.of(first, second),
                        Map.of(first, movie, second, movie), Map.of());
                    check(sharedMovie.size() == 2, "shared poster and encoded media must count once");
                    exceeded = false;
                    try {
                        WorkspaceAppearanceAssets.prepare(null, List.of(first, second),
                            Map.of(first, movie, second, new ThemeAssets.Prepared(new Bitmap(4), null)), Map.of());
                    } catch (IllegalArgumentException expected) { exceeded = true; }
                    check(exceeded, "encoded wallpaper media and other images share one retained budget");
                    Map<ShellResources, ThemeAssets.Prepared> fonts = new LinkedHashMap<>();
                    for (int i = 0; i <= WorkspaceAppearanceAssets.MAX_FONTS; i++) {
                        fonts.put(resource(i + 10), new ThemeAssets.Prepared(null, new Typeface()));
                    }
                    exceeded = false;
                    try { WorkspaceAppearanceAssets.prepare(null, fonts.keySet(), fonts, Map.of()); }
                    catch (IllegalArgumentException expected) { exceeded = true; }
                    check(exceeded && ThemeAssets.calls == 0, "retained fonts must be bounded");
                    var empty = WorkspaceAppearanceAssets.prepare(null, List.of(ShellResources.defaults()), Map.of(), Map.of());
                    check(empty.get(ShellResources.defaults()) == ThemeAssets.Prepared.EMPTY, "asset-free appearance needs no worker or disk");
                    boolean failed = false;
                    try { WorkspaceAppearanceAssets.prepare(null, List.of(first), Map.of(), Map.of()); }
                    catch (IOException expected) { failed = true; }
                    check(failed && ThemeAssets.calls == 1, "failed preparation must not create a partial snapshot");
                }
                """ + RuntimeSourceFixture.nestedClass("WorkspaceAppearanceAssets", "WorkspaceAppearanceAssets")
                        .replace("final class WorkspaceAppearanceAssets", "static final class WorkspaceAppearanceAssets"),
                "ShellResources", "ShaderWallpaper");
    }
}
