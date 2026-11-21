package io.github.mekhontsev.magicdesk;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import static org.junit.Assert.*;

public class HostedKeyboardDataTest {
    @Rule public TemporaryFolder folders = new TemporaryFolder();

    @Test public void packagedDataInstallsFromTheApkAssetAlone() throws Exception {
        Path target = folders.getRoot().toPath().resolve("installed");
        Path asset = Path.of("src/main/assets", BundledKeyboardData.NAME + ".zip");
        try (var input = Files.newInputStream(asset)) { BundledKeyboardData.install(input, target); }
        for (var entry : new String[]{"rules/evdev", "keycodes/evdev", "symbols/us", "types/complete",
                "compat/complete", "COPYING"}) assertTrue(entry, Files.size(target.resolve(entry)) > 0);
    }

    @Test public void invalidAssetIsNotPublished() throws Exception {
        Path parent = folders.getRoot().toPath();
        assertThrows(java.io.IOException.class, () -> BundledKeyboardData.install(
                new ByteArrayInputStream(new byte[0]), parent.resolve("data")));
        try (var paths = Files.list(parent)) { assertEquals(0, paths.count()); }
    }

    @Test public void optionalOverridesDescribeTheActualField() {
        assertEquals("", HostedKeyboardSource.normalize(""));
        assertEquals("/data/xkb", HostedKeyboardSource.normalize("/data/xkb"));
        assertEquals("guest:/linux/test", HostedKeyboardSource.normalize("guest:/linux/test"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> HostedKeyboardSource.normalize("test"))
                .getMessage().contains("XKB"));
        for (String value : new String[]{"guest:wrong", "guest:/", "guest:/path\nline"})
            assertThrows(IllegalArgumentException.class, () -> HostedKeyboardSource.normalize(value));
    }
}
