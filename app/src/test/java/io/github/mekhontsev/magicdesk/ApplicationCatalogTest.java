package io.github.mekhontsev.magicdesk;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.Test;
import static org.junit.Assert.*;

public final class ApplicationCatalogTest {
    @Test public void mergedCatalogDeduplicatesIdentityNotLabelAndSortsDeterministically() throws Exception {
        RuntimeSourceFixture.verify("""
                record StartMenuEntry(String label, String stableKey) { }
                public static void verify() {
                    var android = new StartMenuEntry("Browser", "android|0|browser");
                    var linux = new StartMenuEntry("browser", "termux|browser");
                    var otherProfile = new StartMenuEntry("Browser", "android|10|browser");
                    var duplicate = new StartMenuEntry("stale name", "termux|browser");
                    var first = new StartMenuEntry("A", "android|0|a");
                    var source = new ArrayList<>(List.of(linux, duplicate, otherProfile, android, first));
                    var sorted = sortedUnique(source);
                    check(sorted.equals(List.of(first, android, otherProfile, linux)), "merge lost identity or order");
                    check(source.size() == 5 && source.get(0) == linux, "merge changed source");
                    source.clear();
                    check(sorted.size() == 4, "snapshot changed with source");
                    try { sorted.clear(); throw new AssertionError("mutable catalog"); }
                    catch (UnsupportedOperationException expected) { }
                }
                """ + RuntimeSourceFixture.methods("ApplicationCatalog", "sortedUnique"));
    }

    @Test public void coldStartGatesBothGridAndSearchButNotOnTermuxReadiness() throws Exception {
        final String entries = RuntimeSourceFixture.methods("StartMenuContent", "entries");
        assertTrue(entries.indexOf("!mCatalog.snapshot().android().ready()") < entries.indexOf("mHost.searchEntries"));
        assertFalse(entries.contains("termux().ready()"));
        final String body = RuntimeSourceFixture.methods("StartMenuContent", "renderBody");
        assertTrue(body.indexOf("!android.ready()") < body.indexOf("renderSearchResults()"));
        assertTrue(body.contains("R.string.apps_loading"));
    }

    @Test public void startHostsSubscribeToSharedCatalogInsteadOfOwningDiscovery() throws Exception {
        final String start = source("FullscreenStartController");
        assertTrue(start.contains("ApplicationCatalog.get(activity)"));
        assertFalse(start.contains("LauncherApps.Callback"));
        assertFalse(start.contains("ExecutorService"));
        assertTrue(RuntimeSourceFixture.methods("StartMenuContent", "prepare").contains("mCatalog.subscribe"));
        for (String method : new String[]{"pause", "release"}) {
            assertTrue(RuntimeSourceFixture.methods("StartMenuContent", method).contains("mCatalog.unsubscribe"));
        }
        assertTrue(RuntimeSourceFixture.methods("DesktopShellActivity", "renderApps").contains("mApplicationCatalog.ensureAndroid()"));
        assertFalse(RuntimeSourceFixture.methods("DesktopShellActivity", "renderApps").contains("mLauncherApps.load"));
    }

    @Test public void catalogDoesNotRequireDesktopOrPrivilegeAndAutomationSharesTermuxSource() throws Exception {
        final String catalog = source("ApplicationCatalog");
        for (String dependency : new String[]{"DesktopRuntimeBridge", "DesktopHomeRoleLease",
                "ShellAccess.require", "ShellAccess.isReady", "RuntimeCapabilities.require"}) {
            assertFalse(dependency, catalog.contains(dependency));
        }
        assertTrue(source("DesktopEntrySource").contains("ApplicationCatalog.loadTermux"));
        final String termux = RuntimeSourceFixture.methods("ApplicationCatalog", "loadTermuxSource");
        assertTrue(termux.indexOf("inspectTermux()") < termux.indexOf("complete.complete"));
        assertTrue(termux.contains("!owner.equals(termuxOwner)"));
    }

    private static String source(String name) throws Exception {
        return Files.readString(Path.of(RuntimeSourceFixture.MAIN + name + ".java"));
    }
}
