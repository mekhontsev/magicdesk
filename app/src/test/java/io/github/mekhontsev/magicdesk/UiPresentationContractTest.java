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
    public void desktopGridLeavesKeyboardFocusToItsItems() throws IOException {
        final String grid = read("DesktopGridLayout.java");
        assertTrue(grid.contains("setFocusable(false);"));
        assertTrue(grid.contains("setDefaultFocusHighlightEnabled(false);"));
        assertFalse(grid.contains("FOCUS_BLOCK_DESCENDANTS"));
        assertFalse(grid.contains("setFocusable(true)"));
        assertTrue(read("DesktopItemViewFactory.java").contains("item.setFocusable(true);"));
        assertTrue(read("DesktopWorkspaceController.java").contains(
                "grid.setOnClickListener(view -> clearFileSelection());"));
        assertTrue(grid.contains("setOnDragListener("));
    }

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
        final String probe = RuntimeSourceFixture.methods("ControlActivity", "refreshSelectedOutput");
        final int unavailable = probe.indexOf("if (display == null");
        assertTrue(unavailable >= 0);
        final int generation = probe.indexOf("++mOutputGeneration");
        assertTrue(generation >= 0 && generation < unavailable);
        assertTrue(probe.contains("generation != mOutputGeneration || isActivityUnavailable()"));
        assertTrue(probe.contains("DesktopDisplayCatalog.require(display.id, display.uniqueId)"));
        final String catalog = RuntimeSourceFixture.methods("ControlActivity", "refreshCatalog");
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
        assertTrue(launch.contains("DesktopRuntimeBridge.hasWorkspace(displayId)"));
        assertTrue(launch.contains("&& DesktopRuntimeBridge.openApplicationSettings(displayId, null)"));
        assertTrue(launch.contains("startActivityOnCurrentDisplay("));
    }

    private static String read(final String name) throws IOException {
        return Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/" + name));
    }
}
