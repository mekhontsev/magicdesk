package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FileManagerContractTest {
    @Test
    public void manifestProtectsExportedFileManagerAndProvider()
            throws IOException {
        final String manifest = Files.readString(
                Path.of("src/main/AndroidManifest.xml"),
                StandardCharsets.UTF_8);
        final int activity = manifest.indexOf(
                "android:name=\".FileManagerActivity\"");
        final int activityEnd = manifest.indexOf("/>", activity);
        final String declaration = manifest.substring(activity, activityEnd);
        assertTrue(declaration.contains("android:exported=\"true\""));
        assertTrue(declaration.contains(
                "android:permission=\"android.permission.MANAGE_ACTIVITY_TASKS\""));

        final int provider = manifest.indexOf(
                "android:name=\".ShellFileProvider\"");
        final int providerEnd = manifest.indexOf("/>", provider);
        final String providerDeclaration = manifest.substring(
                provider, providerEnd);
        assertTrue(providerDeclaration.contains(
                "android:exported=\"false\""));
        assertTrue(providerDeclaration.contains(
                "android:grantUriPermissions=\"true\""));
    }

    @Test
    public void aidlKeepsNewMethodsAfterExistingContract() throws IOException {
        final String aidl = Files.readString(
                Path.of("src/main/aidl/io/github/mekhontsev/magicdesk/"
                        + "IShizukuCommandService.aidl"),
                StandardCharsets.UTF_8);
        assertTrue(aidl.contains("listShellDirectory("));
        assertTrue(aidl.contains(") = 49;"));
        assertTrue(aidl.contains("cancelShellFileOperation(long operationId) = 55;"));
        assertTrue(aidl.contains("createAvailableShellEntry("));
        assertTrue(aidl.contains("boolean directory) = 57;"));
        assertTrue(aidl.contains("long inode) = 56;"));
        assertFalse(aidl.substring(
                aidl.indexOf("long startShellFileOperation("),
                aidl.indexOf(") = 54;")).contains("boolean replace"));
    }
}
