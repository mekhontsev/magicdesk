package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class DiagnosticsActivityManifestTest {
    @Test
    public void externalDisplayConfigurationDoesNotRestartSelfTest() throws IOException {
        final String manifest = Files.readString(
                Path.of("src/main/AndroidManifest.xml"),
                StandardCharsets.UTF_8);
        final int activity = manifest.indexOf("android:name=\".DiagnosticsActivity\"");
        assertTrue("DiagnosticsActivity is declared", activity >= 0);
        final int end = manifest.indexOf("/>", activity);
        assertTrue("DiagnosticsActivity declaration is complete", end > activity);
        final String declaration = manifest.substring(activity, end);
        assertTrue(declaration.contains(
                "android:configChanges=\"orientation|screenSize|smallestScreenSize\""));
    }
}
