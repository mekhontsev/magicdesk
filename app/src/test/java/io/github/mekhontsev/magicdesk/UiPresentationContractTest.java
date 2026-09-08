package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class UiPresentationContractTest {
    @Test
    public void fileAdapterDoesNotAdvertiseCollidingPathHashesAsStableIds()
            throws IOException {
        assertEquals("/tmp/Aa".hashCode(), "/tmp/BB".hashCode());
        final String source = read("ShellFileAdapter.java");
        assertFalse(source.contains("hasStableIds()"));
        assertFalse(source.contains("absolutePath.hashCode()"));
        assertTrue(source.contains(
                "public long getItemId(final int position) {\n        return position;"));
    }

    @Test
    public void explorerCompletionsCannotOverwriteANewerView() throws IOException {
        final String source = read("ActivityExplorerActivity.java");
        assertTrue(source.contains("if (!mDestroyed && generation == mPresentationGeneration)"));
        assertTrue(source.contains("present(generation, () -> renderHandlers(result));"));
        assertTrue(source.contains("private void showHistory() {\n        mPresentationGeneration++;"));
        assertEquals(2, source.split("final int generation = \\+\\+mPresentationGeneration;", -1)
                .length - 1);
        assertEquals(2, source.split("showError\\(generation, error\\);", -1).length - 1);
        assertEquals(1, source.split("runOnUiThread\\(", -1).length - 1);
    }

    @Test
    public void unavailableControlStateInvalidatesAlreadyRunningDisplayProbes()
            throws IOException {
        final String source = read("ControlActivity.java");
        final String probe = source.substring(
                source.indexOf("private void refreshSelectedOutput()"),
                source.indexOf("private boolean isExternalDesktopActive()"));
        final int unavailable = probe.indexOf("if (display == null");
        assertTrue(unavailable >= 0);
        assertTrue(probe.indexOf("++mOutputGeneration") < unavailable);
        assertTrue(probe.contains("generation != mOutputGeneration || isActivityUnavailable()"));
        assertTrue(probe.contains("DesktopDisplayCatalog.require(display.id, display.uniqueId)"));
        final String catalog = source.substring(source.indexOf("private void refreshCatalog()"),
                source.indexOf("private void refreshCatalog()") + 500);
        assertTrue(catalog.contains("++mCatalogGeneration"));
        assertTrue(catalog.contains("generation != mCatalogGeneration || isActivityUnavailable()"));
    }

    @Test
    public void applicationSettingsUseTheDesktopOnlyOnTheCurrentDisplay()
            throws IOException {
        final String source = read("SettingsActivity.java");
        final String launch = source.substring(source.indexOf("public void openApplicationSettings()"),
                source.indexOf("public void openDiagnostics()"));
        assertTrue(launch.contains("final android.view.Display display = getDisplay();"));
        assertTrue(launch.contains("if (displayId == DesktopRuntimeBridge.getActiveDesktopDisplayId()"));
        assertTrue(launch.contains("&& DesktopRuntimeBridge.openApplicationSettings(null)"));
        assertTrue(launch.contains("startActivityOnCurrentDisplay("));
    }

    private static String read(final String name) throws IOException {
        return Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/" + name));
    }
}
