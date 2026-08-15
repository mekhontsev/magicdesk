package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class SettingsActivityManifestTest {
    @Test
    public void settingsUsesProtectedDedicatedResizableTask() throws IOException {
        final String manifest = Files.readString(
                Path.of("src/main/AndroidManifest.xml"),
                StandardCharsets.UTF_8);
        final int activity = manifest.indexOf(
                "android:name=\".SettingsActivity\"");
        assertTrue("SettingsActivity is declared", activity >= 0);
        final int end = manifest.indexOf("/>", activity);
        assertTrue("SettingsActivity declaration is complete", end > activity);
        final String declaration = manifest.substring(activity, end);
        assertTrue(declaration.contains(
                "android:exported=\"true\""));
        assertTrue(declaration.contains(
                "android:permission=\"android.permission.MANAGE_ACTIVITY_TASKS\""));
        assertTrue(declaration.contains(
                "android:launchMode=\"singleTask\""));
        assertTrue(declaration.contains(
                "android:resizeableActivity=\"true\""));
        assertTrue(declaration.contains(
                "android:taskAffinity=\"${applicationId}.settings\""));
    }
}
