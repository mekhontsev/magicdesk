package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

public final class StartSearchControllerTest {
    @Test
    public void builtInResultsHaveDistinctSemanticIdsDespiteSharedDetail() {
        final Set<String> ids = new HashSet<>();
        for (final BuiltInDesktopAppCatalog.Entry entry
                : BuiltInDesktopAppCatalog.searchEntries()) {
            final StartSearchController.Result result =
                    StartSearchController.Result.builtIn("Same label", entry);
            assertEquals("MagicDesk", result.detail);
            assertTrue(ids.add(DesktopAutomationUiRegistry.identitySegment(result.stableKey())));
            assertEquals(result.stableKey(), StartSearchController.Result.builtIn(
                    "Localized label", entry).stableKey());
        }
    }

    @Test
    public void actionsHaveDistinctSemanticIdsDespiteSharedDetail() {
        final Set<String> ids = new HashSet<>();
        for (final StartSearchController.Action action
                : StartSearchController.Action.values()) {
            final StartSearchController.Result result =
                    StartSearchController.Result.action("Same label", action);
            assertEquals("Action", result.detail);
            assertTrue(ids.add(DesktopAutomationUiRegistry.identitySegment(result.stableKey())));
            assertEquals(result.stableKey(), StartSearchController.Result.action(
                    "Localized label", action).stableKey());
        }
    }

    @Test
    public void commandIdentityUsesItsEntryPathNotTheDisplayedCommand() {
        final DesktopApplicationShortcut shortcut = new DesktopCommandApplicationDraft(
                "Command", "pwd", DesktopExecBackend.SHELL, "",
                DesktopCommandApplicationDraft.FileArguments.NONE, "").build();
        final StartSearchController.Result first = StartSearchController.Result
                .desktopApplication(new DesktopApplicationRepository.Entry(
                        shortcut, "/Desktop/first.desktop", null));
        final StartSearchController.Result second = StartSearchController.Result
                .desktopApplication(new DesktopApplicationRepository.Entry(
                        shortcut, "/Desktop/second.desktop", null));

        assertEquals(first.detail, second.detail);
        assertNotEquals(first.stableKey(), second.stableKey());
    }

    @Test
    public void genericSearchRowsRegisterTheResultIdentity() throws IOException {
        final String source = read("StartMenuContent.java");
        assertTrue(source.contains("result.stableKey()"));
    }

    @Test
    public void commandSemanticIdsPreservePathCaseAndSeparators() {
        final DesktopApplicationShortcut shortcut = new DesktopCommandApplicationDraft(
                "Command", "pwd", DesktopExecBackend.SHELL, "",
                DesktopCommandApplicationDraft.FileArguments.NONE, "").build();
        final Set<String> commandIds = new HashSet<>();
        final Set<String> resultIds = new HashSet<>();
        for (final String path : new String[] {
                "/Desktop/Aa.desktop", "/Desktop/aa.desktop", "/Desktop/a b.desktop",
                "/Desktop/a/b.desktop", "/Desktop/a-b.desktop"}) {
            final StartSearchController.Result result = StartSearchController.Result
                    .desktopApplication(new DesktopApplicationRepository.Entry(shortcut, path, null));
            assertTrue(commandIds.add("start.search.command."
                    + DesktopAutomationUiRegistry.identitySegment(path)));
            assertTrue(resultIds.add("start.search.result."
                    + DesktopAutomationUiRegistry.identitySegment(result.stableKey())));
        }
    }

    @Test
    public void startAndTaskbarEncodeIdentitiesWithoutChangingReadableLabels() throws IOException {
        final String start = read("StartMenuContent.java").replaceAll("\\s+", "");
        for (final String identity : new String[] {
                "application.identity()", "result.app.packageName",
                "result.desktopApplication.desktopFilePath", "result.stableKey()"}) {
            assertTrue(start.contains("DesktopAutomationUiRegistry.identitySegment(" + identity + ")"));
        }
        assertTrue(read("TaskbarController.java").replaceAll("\\s+", "").contains(
                "DesktopAutomationUiRegistry.identitySegment(app.packageName)"));
        assertTrue(read("TaskbarOverflowController.java").replaceAll("\\s+", "").contains(
                "DesktopAutomationUiRegistry.identitySegment(item.app.packageName)"));
        assertTrue(read("DesktopContextMenuController.java").contains(
                "DesktopAutomationUiRegistry.segment(text)"));
    }

    @Test
    public void utilitySearchResultsUseExistingDesktopLaunchActions() throws IOException {
        final String source = read("StartMenuController.java");
        assertTrue(source.contains("BuiltInDesktopAppCatalog.appPresentationSettingsTarget()"));
        assertTrue(source.contains("mActivity.openApplicationSettings(null);"));
        assertTrue(source.contains("BuiltInDesktopAppCatalog.diagnosticsTarget()"));
        assertTrue(source.contains("mActivity.openDiagnostics();"));
        assertTrue(source.contains("BuiltInDesktopAppCatalog.activityExplorerTarget()"));
        assertTrue(source.contains("mActivity.openActivityExplorer();"));
    }

    private static String read(final String name) throws IOException {
        return Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/" + name));
    }
}
