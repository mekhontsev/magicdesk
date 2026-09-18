package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.Assert.*;

public final class ApplicationDisplayCatalogTest {
    @Test public void publicInventoryRetainsOnlyConnectionIdentities() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Display {
                    static final int DEFAULT_DISPLAY = 0, FLAG_SECURE = 2;
                    int id; boolean valid = true; Display(int id) { this.id = id; }
                    boolean isValid() { return valid; } int getDisplayId() { return id; }
                    String getName() { return "display"; } int getFlags() { return FLAG_SECURE; }
                    void getRealMetrics(DisplayMetrics m) { m.widthPixels = 1920; m.heightPixels = 1080; m.densityDpi = 160; }
                }
                static class DisplayMetrics { int widthPixels, heightPixels, densityDpi; }
                static class DisplayNames { static String name(Display d) { return d.getName(); } }
                static class DisplayManager {
                    Display[] displays = { new Display(0), new Display(3) };
                    Display[] getDisplays() { return displays; }
                }
                record DesktopDisplayInfo(int id, String uniqueId, String systemName, String name,
                    String source, int width, int height, int dpi, boolean desktop, boolean portable,
                    boolean owned, boolean secure) { }
                final DisplayManager manager = new DisplayManager();
                final Map<Integer, String> identities = new HashMap<>();
                """ + RuntimeSourceFixture.methods("ApplicationDisplayCatalog", "snapshot", "onDisplayRemoved", "onDisplayChanged") + """
                public static void verify() {
                    Fixture f = new Fixture();
                    var first = f.snapshot(); var second = f.snapshot();
                    check(first.length == 2 && first[0].source().equals("phone")
                            && first[1].source().equals("unknown"), "guessed hidden display type");
                    check(first[1].uniqueId().equals(second[1].uniqueId()), "identity changed during refresh");
                    check(!first[1].desktop() && !first[1].owned(), "invented privilege or ownership");
                    f.onDisplayChanged(3);
                    check(first[1].uniqueId().equals(f.snapshot()[1].uniqueId()), "mode change lost identity");
                    f.onDisplayRemoved(3);
                    check(!first[1].uniqueId().equals(f.snapshot()[1].uniqueId()), "reused ID kept stale address");
                    f.manager.displays[1].valid = false;
                    check(f.snapshot().length == 1 && f.identities.size() == 1, "removed display retained");
                }
                """);
    }

    @Test public void publicInventoryDoesNotUseHiddenApisOrPrivilegeTransport() throws Exception {
        final String source = Files.readString(Path.of(RuntimeSourceFixture.MAIN + "ApplicationDisplayCatalog.java"));
        for (String forbidden : new String[] {"ShellAccess", "FrameworkRuntime", "getMethod", "getField",
                "DesktopStateStore", "DesktopRuntimeBridge"}) assertFalse(forbidden, source.contains(forbidden));
        final String catalog = RuntimeSourceFixture.methods("DesktopDisplayCatalog", "read");
        assertTrue(catalog.indexOf("ApplicationDisplayCatalog.read()") < catalog.indexOf("ShellAccess.listDesktopDisplays()"));
        assertFalse(RuntimeSourceFixture.methods("StartDisplaySelector", "show").contains("ShellAccess.isReady()"));
    }
}
