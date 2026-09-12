package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class StartDestinationTest {
    @Test public void visibleStartRemainsAddressableWhenAnotherDisplayHasKeyboardFocus() throws Exception {
        RuntimeSourceFixture.verify("""
                static class View {
                    static final int VISIBLE = 0;
                    int id;
                    boolean attached = true, shown = true, focused;
                    int visibility;
                    boolean isAttachedToWindow() { return attached; }
                    boolean isShown() { return shown; }
                    boolean hasWindowFocus() { return focused; }
                    int getWindowVisibility() { return visibility; }
                    View getDisplay() { return this; }
                    int getDisplayId() { return id; }
                }
                static class Window {
                    View decor = new View();
                    View peekDecorView() { return decor; }
                }
                static class DesktopAutomationUiRegistry { }
                static class DesktopShellActivity {
                    int getCurrentDisplayId() { return -1; }
                    DesktopAutomationUiRegistry automationUi() { return null; }
                }
                final Map<Window, DesktopAutomationUiRegistry> mUiWindows = new LinkedHashMap<>();
                DesktopShellActivity usableDesktop(int id, boolean wait) { return null; }
                public static void verify() {
                    Fixture f = new Fixture();
                    Window phone = new Window(), external = new Window();
                    external.decor.id = 7;
                    external.decor.focused = true;
                    DesktopAutomationUiRegistry a = new DesktopAutomationUiRegistry();
                    DesktopAutomationUiRegistry b = new DesktopAutomationUiRegistry();
                    f.mUiWindows.put(phone, a);
                    f.mUiWindows.put(external, b);
                    check(f.automationUi(0) == a, "visible phone Start lost after external focus");
                    check(f.automationUi(7) == b, "wrong external registry");
                    check(f.automationUi(8) == null, "registry leaked across displays");
                    Window second = new Window();
                    f.mUiWindows.put(second, b);
                    check(f.automationUi(0) == null, "ambiguous windows picked arbitrarily");
                    phone.decor.focused = true;
                    check(f.automationUi(0) == a, "focused window lost priority");
                    phone.decor.focused = false;
                    second.decor.shown = false;
                    check(f.automationUi(0) == a, "hidden window caused ambiguity");
                    phone.decor.visibility = 8;
                    check(f.automationUi(0) == null, "hidden Start exposed");
                }
                """ + RuntimeSourceFixture.methods("DesktopUiGateway", "automationUi")
                        .replace("android.view.View", "View").replace("android.view.Window", "Window"));
    }

    @Test public void eachStartKeepsItsOwnDestinationAndCurrentFollowsItsHost() throws Exception {
        RuntimeSourceFixture.verify("""
                record Target(int displayId, String uniqueId) { }
                static class Display {
                    int id;
                    int getDisplayId() { return id; }
                }
                static class Activity {
                    Display display = new Display();
                    Display getDisplay() { return display; }
                }
                static class DesktopDisplayInfo {
                    int id; String uniqueId;
                    DesktopDisplayInfo(int id, String uniqueId) { this.id = id; this.uniqueId = uniqueId; }
                }
                Activity mActivity = new Activity();
                DesktopDisplayInfo mSelected;
                int labelUpdates;
                void updateLabel() { labelUpdates++; }
                public static void verify() {
                    Fixture phone = new Fixture();
                    Fixture external = new Fixture();
                    external.mActivity.display.id = 7;
                    check(phone.target().displayId() == 0, "phone Current is not its host");
                    check(external.target().displayId() == 7, "external Current is not its host");
                    phone.select(new DesktopDisplayInfo(8, "virtual:first"));
                    Target queued = phone.target();
                    check(queued.displayId() == 8 && queued.uniqueId().equals("virtual:first"), "identity lost");
                    check(external.target().displayId() == 7, "selection leaked to another Start");
                    phone.select(new DesktopDisplayInfo(8, "virtual:replacement"));
                    check(queued.uniqueId().equals("virtual:first"), "pending launch retargeted");
                    phone.select(null);
                    phone.mActivity.display.id = 3;
                    check(phone.target().displayId() == 3, "Current is cached instead of following host");
                }
                """ + RuntimeSourceFixture.methods("StartDisplaySelector", "target", "select"));
    }

    @Test public void removedDestinationCannotRedirectToReusedDisplayId() throws Exception {
        RuntimeSourceFixture.verify("""
                static class DesktopDisplayInfo {
                    int id; String uniqueId;
                    DesktopDisplayInfo(int id, String uniqueId) { this.id = id; this.uniqueId = uniqueId; }
                }
                static DesktopDisplayInfo[] live = { new DesktopDisplayInfo(8, "replacement") };
                static DesktopDisplayInfo[] read() { return live; }
                public static void verify() throws Exception {
                    boolean rejected = false;
                    try { require(8, "original"); } catch (IOException expected) { rejected = true; }
                    check(rejected, "stale destination accepted");
                    check(require(8, "replacement") == live[0], "live destination rejected");
                    live = new DesktopDisplayInfo[0];
                    rejected = false;
                    try { require(8, "replacement"); } catch (IOException expected) { rejected = true; }
                    check(rejected, "disconnected display accepted");
                }
                """ + RuntimeSourceFixture.methods("DesktopDisplayCatalog", "require"));
    }

    @Test public void ordinaryStartDoesNotAcquireHomeOrDesktop() throws Exception {
        final String start = RuntimeSourceFixture.methods("StartActivity", "open", "onCreate");
        assertTrue(start.contains("new FullscreenStartController(this, false)"));
        assertFalse(start.contains("DesktopOperations"));
        assertFalse(start.contains("DesktopHomeRoleLease"));
        final String panel = RuntimeSourceFixture.methods("ControlActivity", "openApplications");
        assertTrue(panel.contains("StartActivity.open(this)"));
        assertFalse(panel.contains("selectedDisplay"));
        final String selection = RuntimeSourceFixture.methods("StartDisplaySelector", "select");
        assertFalse(selection.contains("showSection"));
        assertFalse(selection.contains("setText"));
        assertFalse(selection.contains("DesktopOperations"));
    }
}
