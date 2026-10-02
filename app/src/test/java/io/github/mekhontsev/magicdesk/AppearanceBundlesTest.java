package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class AppearanceBundlesTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aZ1cAAAAASUVORK5CYII=");
    private Path directory;
    private ThemeBundleStore store;

    @Before public void setup() throws Exception {
        directory = Files.createTempDirectory("appearance-bundles-");
        store = new ThemeBundleStore(directory, (path, kind, bytes, limits, remaining) -> new ThemeBundle.ImageSize(1, 1));
    }
    @After public void cleanup() throws Exception { ThemeBundleFiles.deleteTree(directory); }

    @Test public void editedThemeAndReferencedAssetsRoundTripWithoutExternalDigest() throws Exception {
        var theme = AppearanceBundles.read(store, archive("{\"version\":5,\"resources\":{\"iconAssets\":{\"files\":\"icons/test.png\"}}}",
                Map.of("icons/test.png", PNG, "icons/unused.png", PNG)));
        assertTrue(theme.resources().bundle().matches("[a-f0-9]{64}"));
        var edited = theme.withTypography(new ShellAppearance.Typography(ShellAppearance.Font.SERIF, 1.2f));
        var output = new ByteArrayOutputStream();
        AppearanceBundles.write(store, edited, output);
        var roundTrip = AppearanceBundles.read(store, new ByteArrayInputStream(output.toByteArray()));
        assertEquals(edited.typography(), roundTrip.typography());
        assertEquals(edited.composition(), roundTrip.composition());
        var receipt = store.open(roundTrip.resources().bundle());
        assertEquals(3, receipt.entries().size());
        assertEquals("", ShellAppearanceJson.parse(receipt.themeJson()).resources().bundle());
        assertArrayEquals(PNG, receipt.readAsset("icons/test.png", ThemeBundle.Kind.ICON));
    }

    @Test public void missingWrongKindAndExternalResourcesAreRejectedBeforePublication() throws Exception {
        for (String document : new String[] {
                "{\"resources\":{\"iconAssets\":{\"files\":\"icons/missing.png\"}}}",
                "{\"resources\":{\"wallpaper\":\"icons/test.png\"}}",
                "{\"resources\":{\"bundle\":\"" + "a".repeat(64) + "\"}}",
                "{\"composition\":{\"panels\":[]}}"
        }) assertThrows(IOException.class, () -> AppearanceBundles.read(store, archive(document, Map.of("icons/test.png", PNG))));
        try (var files = Files.list(directory)) {
            assertEquals(0, files.filter(path -> path.getFileName().toString().matches("[a-f0-9]{64}")).count());
        }
    }

    @Test public void unbundledAppearanceExportsAndStreamsRemainCallerOwned() throws Exception {
        boolean[] closed = {false};
        var output = new ByteArrayOutputStream() { @Override public void close() { closed[0] = true; } };
        AppearanceBundles.write(store, ShellAppearance.preset("light"), output);
        assertFalse(closed[0]);
        var restored = AppearanceBundles.read(store, new ByteArrayInputStream(output.toByteArray()));
        assertEquals(ShellAppearance.preset("light").palette(), restored.palette());
    }

    @Test public void unavailableAssetBundleFailsBeforeWriting() throws Exception {
        var theme = ShellAppearance.defaults().withResources(new ShellResources(Map.of(), "a".repeat(64),
                Map.of(ShellResources.Icon.FILES, "icons/test.png"), "", "", null));
        var output = new ByteArrayOutputStream();
        assertThrows(IOException.class, () -> AppearanceBundles.write(store, theme, output));
        assertEquals(0, output.size());
    }

    private static ByteArrayInputStream archive(String document, Map<String, byte[]> assets) throws IOException {
        var output = new ByteArrayOutputStream();
        try (var zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("theme.json")); zip.write(document.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            for (var asset : assets.entrySet()) {
                zip.putNextEntry(new ZipEntry(asset.getKey())); zip.write(asset.getValue()); zip.closeEntry();
            }
        }
        return new ByteArrayInputStream(output.toByteArray());
    }
}
