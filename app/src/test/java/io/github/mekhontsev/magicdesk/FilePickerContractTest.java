package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public final class FilePickerContractTest {
    private static String source(final String name) throws Exception {
        return Files.readString(Path.of("src/main/java/io/github/mekhontsev/magicdesk/" + name + ".java"));
    }

    @Test
    public void pickerUsesResultNotificationAndTheExistingWorker() throws Exception {
        final String importer = source("FileManagerImportController");
        final String activity = source("FileManagerActivity");
        assertTrue(activity.contains("mImporter.chooseFiles(mCurrentPath, currentDisplayId())"));
        assertFalse(activity.contains("AndroidActivityResultStore.get("));
        assertTrue(importer.contains("AndroidActivityResultStore.whenReady(id,"));
        assertTrue(importer.contains("AndroidActivityResultStore.claim(id)"));
        assertFalse(importer.contains("AndroidActivityResultStore.get("));
        assertFalse(importer.contains("600_000"));
        assertFalse(importer.contains("Executors."));
        assertFalse(importer.contains("new Thread("));
        assertFalse(importer.contains(".get(10,"));
    }

    @Test
    public void lateLaunchAndClosedOwnerDiscardTheRequest() throws Exception {
        final String importer = source("FileManagerImportController");
        final String launched = importer.substring(importer.indexOf("private void onPickerLaunched("),
                importer.indexOf("private void readPickerResult("));
        assertTrue(launched.contains("if (mClosed || failure != null || id.isEmpty())"));
        assertTrue(launched.indexOf("AndroidActivityResultStore.discard(id)")
                < launched.indexOf("mPickerRequestId = id"));
        final String close = importer.substring(importer.indexOf("public void close()"),
                importer.indexOf("private static List<Uri> resultUris("));
        assertTrue(close.contains("AndroidActivityResultStore.discard(mPickerRequestId)"));
        assertTrue(close.contains("mImports.close()"));
    }

    @Test
    public void grantOwnershipPassesToTheImportCompletion() throws Exception {
        final String importer = source("FileManagerImportController");
        final String result = importer.substring(importer.indexOf("private void onPickerResult("),
                importer.indexOf("    private void importFiles("));
        final int transfer = result.indexOf("importFiles(destination, uris, null,");
        assertTrue(transfer > result.indexOf("mPickerRequestId = null"));
        assertTrue(result.contains("importFiles(destination, uris, null, owned::close)"));
        assertTrue(result.contains("owned.close()"));
        assertTrue(result.contains("mClosed || !id.equals(mPickerRequestId)"));
    }

    @Test
    public void failedLaunchStillExposesTheResultRequestOrDiscardsItOnException() throws Exception {
        final String gateway = source("AndroidIntegrationGateway");
        final String launch = gateway.substring(gateway.indexOf("private DesktopAutomationResult launchActivity("),
                gateway.indexOf("private DesktopAutomationResult launchResolvedActivity("));
        assertTrue(launch.contains("result.success ? result.data : result.observation"));
        assertTrue(launch.contains(".put(\"requestId\", resultRequestId)"));
        assertTrue(launch.contains("AndroidActivityResultStore.discard(resultRequestId)"));
    }
}
