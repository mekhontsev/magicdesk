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
            final StartMenuEntry result =
                    StartMenuEntry.builtIn("Same label", entry);
            assertEquals("MagicDesk", result.detail);
            assertTrue(ids.add(DesktopAutomationUiRegistry.identitySegment(result.stableKey())));
            assertEquals(result.stableKey(), StartMenuEntry.builtIn(
                    "Localized label", entry).stableKey());
        }
    }

    @Test
    public void actionsHaveDistinctSemanticIdsDespiteSharedDetail() {
        final Set<String> ids = new HashSet<>();
        for (final StartMenuEntry.Action action
                : StartMenuEntry.Action.values()) {
            final StartMenuEntry result =
                    StartMenuEntry.action("Same label", action);
            assertEquals("Action", result.detail);
            assertTrue(ids.add(DesktopAutomationUiRegistry.identitySegment(result.stableKey())));
            assertEquals(result.stableKey(), StartMenuEntry.action(
                    "Localized label", action).stableKey());
        }
    }

    @Test
    public void commandIdentityUsesItsEntryPathNotTheDisplayedCommand() {
        final DesktopApplicationShortcut shortcut = new DesktopCommandApplicationDraft(
                "Command", "pwd", DesktopExecBackend.SHELL, "",
                DesktopCommandApplicationDraft.FileArguments.NONE, "").build();
        final StartMenuEntry first = StartMenuEntry
                .desktopApplication(new DesktopApplicationRepository.Entry(
                        shortcut, "/Desktop/first.desktop", null));
        final StartMenuEntry second = StartMenuEntry
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
            final StartMenuEntry result = StartMenuEntry
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
                "application.stableKey()",
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
    public void utilitySearchResultsUseSharedPlacement() throws IOException {
        final String source = read("StartMenuController.java");
        assertTrue(source.contains("StartEntryLauncher.open(mActivity, result, mContent.destination()"));
        final String launcher = read("StartEntryLauncher.java");
        assertTrue(launcher.contains("entry.builtIn.launchTarget.resolve"));
        assertTrue(launcher.contains("ToolApplications.open(activity, intent, placement(destination)"));
        assertTrue(launcher.contains("destination.uniqueId()"));
    }

    private static String read(final String name) throws IOException {
        return Files.readString(Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/" + name));
    }
}
