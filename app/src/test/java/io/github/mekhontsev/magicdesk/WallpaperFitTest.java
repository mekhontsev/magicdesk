package io.github.mekhontsev.magicdesk;

import org.json.JSONObject;
import org.junit.Test;
import java.util.Map;
import static org.junit.Assert.*;

public final class WallpaperFitTest {
    @Test public void missingFitDefaultsToCoverAndRoundTrips() throws Exception {
        var value = ShellAppearanceJson.parse("{\"resources\":{\"wallpaper\":\"wallpapers/a.png\"}}");
        assertEquals(ShellResources.WallpaperFit.COVER, value.resources().fit());
        var encoded = ShellAppearanceJson.encode(value);
        ShellAppearanceSchema.validate(encoded);
        assertEquals("cover", encoded.getJSONObject("resources").getString("wallpaperFit"));
        assertEquals(value, ShellAppearanceJson.parse(encoded.toString()));
    }

    @Test public void explicitContainSurvivesEncodeAndParse() throws Exception {
        var value = ShellAppearanceJson.parse("{\"resources\":{\"wallpaperFit\":\"contain\"}}");
        assertEquals(ShellResources.WallpaperFit.CONTAIN, value.resources().fit());
        var encoded = ShellAppearanceJson.encode(value);
        assertEquals("contain", encoded.getJSONObject("resources").getString("wallpaperFit"));
        ShellAppearanceSchema.validate(encoded);
        assertEquals(value, ShellAppearanceJson.parse(encoded.toString()));
    }

    @Test public void schemaRejectsUnknownFitValuesAndNonStrings() {
        assertThrows(IllegalArgumentException.class, () -> ShellAppearanceSchema.validate(new JSONObject()
                .put("version", 6).put("resources", new JSONObject().put("wallpaperFit", "stretch"))));
        assertThrows(IllegalArgumentException.class, () -> ShellAppearanceSchema.validate(new JSONObject()
                .put("version", 6).put("resources", new JSONObject().put("wallpaperFit", 3))));
    }

    @Test public void defaultsAndBundlePreserveFit() {
        assertEquals(ShellResources.WallpaperFit.COVER, ShellResources.defaults().fit());
        assertEquals(ShellResources.WallpaperFit.COVER,
                ShellResources.defaults().withBundle("a".repeat(64)).fit());
        assertThrows(IllegalArgumentException.class,
                () -> new ShellResources(Map.of(), "", Map.of(), "", "", null, null));
    }
}
