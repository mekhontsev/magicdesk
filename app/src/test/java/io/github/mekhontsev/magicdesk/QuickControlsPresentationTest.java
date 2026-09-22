package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Test;

public final class QuickControlsPresentationTest {
    @Test
    public void compactPanelUsesContentHeightAboveTaskbar() throws Exception {
        verifyPlacement("""
                f.toggle();
                check(f.mActivity.panels.left == 1552 && f.mActivity.panels.top == 588,
                        "panel not anchored above taskbar");
                check(f.mActivity.panels.width == 360 && f.mActivity.panels.height == 420,
                        "panel expanded into a full-height sidebar");
                f.mActivity.panels.requested = true;
                f.toggle();
                check(f.mActivity.hidden == 1 && f.renders == 1, "toggle rebuilt an open panel");
                """);
    }

    @Test
    public void shortNarrowScreenConstrainsScrollableContent() throws Exception {
        verifyPlacement("""
                f.mActivity.width = 300; f.mActivity.height = 420;
                f.mActivity.left = 20; f.mActivity.top = 30;
                f.mPanel.contentHeight = 900;
                f.toggle();
                check(f.mActivity.panels.width == 284 && f.mActivity.panels.height == 340,
                        "oversized controls escaped available space");
                check(f.mActivity.panels.left == 28 && f.mActivity.panels.top == 38,
                        "viewport origin or margins were lost");
                """);
    }

    @Test
    public void measurementUsesDisplayDensity() throws Exception {
        verifyPlacement("""
                f.mUi.density = 3;
                f.mActivity.width = 1122; f.mActivity.height = 2200; f.mActivity.bar = 192;
                f.mPanel.contentHeight = 3000;
                f.toggle();
                check(f.mActivity.panels.width == 1074 && f.mActivity.panels.height == 1960,
                        "density was ignored while sizing controls");
                check(f.mActivity.panels.left == 24 && f.mActivity.panels.top == 24,
                        "scaled margins are incorrect");
                """);
    }

    @Test
    public void settingsAndQuickControlsHaveDistinctEntryPoints() throws Exception {
        final String panel = source("SystemPanelController");
        assertTrue(panel.contains("R.string.section_quick_controls"));
        assertTrue(panel.contains("R.drawable.ic_settings, R.string.action_settings"));
        assertTrue(panel.contains("mActivity.openSettings()"));
        assertTrue(panel.contains("mActivity.setHardwarePanelVisible(false)"));
        assertTrue(panel.contains("mUi.menuSurface()"));
        assertFalse(panel.contains("new Handler"));
        assertTrue(source("TaskbarController").contains("R.drawable.ic_quick_controls"));
        final String audio = source("DesktopAudioPanelController");
        assertTrue(audio.contains("invokeDesktopAction(\"sound-settings\")"));
        assertTrue(audio.contains("R.string.audio_sound_settings"));
    }

    @Test
    public void desktopToolsCloseOnlyTheirDesktopAndLeaveGlobalExitToControlPanel() throws Exception {
        final String tools = RuntimeSourceFixture.methods("DesktopControlsController", "populateTools");
        assertTrue(tools.contains("mActivity.closeDesktop()"));
        assertTrue(tools.contains("mActivity.openControlPanel()"));
        assertFalse(tools.contains("R.string.action_exit"));
        assertFalse(source("DesktopShellActivity").contains("exitMagicDesk"));
        assertFalse(source("DesktopShellActivity").contains("mSessionController.exit()"));
        final String controlPanel = source("PhoneControlPanelController");
        assertTrue(controlPanel.contains("R.string.confirm_exit_magicdesk"));
        assertTrue(controlPanel.contains("mActions.exitMagicDesk()"));
    }

    @Test
    public void phonePanelKeepsActionsInPlaceAndUsesExistingHandlers() throws Exception {
        final String render = RuntimeSourceFixture.methods("PhoneControlPanelController", "render");
        assertTrue(render.contains("mLocalApps.setVisibility(state.displays.length > 0 ? View.GONE : View.VISIBLE)"));
        assertFalse(render.contains("mCreateDisplay.setVisibility"));
        assertFalse(render.contains("mConnectWirelessDisplay.setVisibility"));
        assertFalse(render.contains("removeView"));
        assertTrue(render.contains("mDisplayTable.render(state.displays, state.desktopDisplays"));
        final String create = RuntimeSourceFixture.methods("PhoneControlPanelController", "createView");
        assertTrue(create.indexOf("addDesktopActions(content)") < create.indexOf("addSystemActions(content)"));
        final String controller = source("PhoneControlPanelController");
        assertTrue(controller.contains("mActions.openSettings()"));
        assertTrue(source("DisplayTableView").contains("mActions.closeDesktop(display)"));
        assertTrue(controller.contains("mActions.exitMagicDesk()"));
        assertFalse(controller.contains("mActions.openApplications()"));
        assertTrue(source("DisplayTableView").contains("mActions.openApplications(display)"));
        assertTrue(source("DisplayTableView").contains("mActions.controlDisplay(display)"));
        assertTrue(controller.contains("mActions.releaseInput()"));
    }

    @Test
    public void phoneWirelessActionSharesTheGlobalActionsGrid() throws Exception {
        final String header = RuntimeSourceFixture.methods("PhoneControlPanelController", "createHeader");
        assertFalse(header.contains("mConnectWirelessDisplay"));
        final String desktop = RuntimeSourceFixture.methods("PhoneControlPanelController", "addDesktopActions");
        assertTrue(desktop.contains("mActions.openWirelessSettings()"));
        assertTrue(desktop.contains("addGridAction(sessionActions, mReleaseInput)"));
        assertTrue(desktop.contains("addGridAction(sessionActions, mConnectWirelessDisplay)"));
        assertFalse(desktop.contains("parent.addView(mConnectWirelessDisplay"));
        assertFalse(desktop.contains("parent.addView(mCloseDesktop"));
        final String render = RuntimeSourceFixture.methods("PhoneControlPanelController", "render");
        assertTrue(render.contains("mConnectWirelessDisplay.setEnabled(state.wirelessConnectionUiAvailable"));
        assertFalse(render.contains("!state.desktopSessionActive"));
        assertFalse(source("PhoneControlPanelController").contains("wirelessDisplayConnected"));
        assertTrue(render.contains("!state.sessionOperationInProgress"));
        assertTrue(render.contains("!state.displayOperation"));
    }

    @Test
    public void x11SessionSelectionLeavesOpeningToExplicitActions() throws Exception {
        final String selection = RuntimeSourceFixture.methods("GraphicalSessionsActivity", "chooseSession");
        assertTrue(selection.contains("select(items.get(which))"));
        assertFalse(selection.contains("openWindow("));
        final String controls = RuntimeSourceFixture.methods("GraphicalSessionsActivity", "createSessionControls")
                .replaceAll("\\s+", "");
        assertTrue(controls.contains("R.string.x11_open_session,()->openWindow(0)"));
        assertTrue(controls.contains("R.string.graphics_windows,this::chooseWindow"));
        assertTrue(RuntimeSourceFixture.methods("GraphicalSessionsActivity", "chooseWindow")
                .contains("openWindow(windows.get(which).id())"));
    }

    @Test
    public void readyTerminalDoesNotExposeTransportImplementation() throws Exception {
        final String ready = RuntimeSourceFixture.methods("CommandConsoleActivity", "onReady");
        assertTrue(ready.contains("mTerminalStatus = \"\""));
        assertFalse(source("CommandConsoleActivity").contains("console_terminal_ready"));
    }

    private static String source(final String name) throws Exception {
        return Files.readString(Path.of(RuntimeSourceFixture.MAIN + name + ".java"));
    }

    private static void verifyPlacement(final String scenario) throws Exception {
        RuntimeSourceFixture.verify("""
                static class R { static class string {
                    static int section_quick_controls = 1, status_desktop_panel_unavailable = 2;
                } }
                static class View { static class MeasureSpec {
                    static int EXACTLY = 1, AT_MOST = 2;
                    static int makeMeasureSpec(int size, int mode) { return size << 2 | mode; }
                } }
                static class Panel {
                    int contentHeight = 420, height;
                    void measure(int width, int limit) {
                        check((width & 3) == 1 && (limit & 3) == 2, "wrong measurement contract");
                        height = Math.min(contentHeight, limit >> 2);
                    }
                    int getMeasuredHeight() { return height; }
                }
                static class DesktopPanelWindowController {
                    int left, top, width, height;
                    boolean requested;
                    boolean isRequested(Panel panel) { return requested; }
                    boolean show(Panel panel, int l, int t, int w, int h, boolean focus, String title) {
                        left = l; top = t; width = w; height = h;
                        check(!focus, "quick controls unexpectedly stole app focus");
                        return true;
                    }
                }
                static class Activity {
                    final DesktopPanelWindowController panels = new DesktopPanelWindowController();
                    int width = 1920, height = 1080, bar = 64, left, top, hidden;
                    DesktopPanelWindowController panels() { return panels; }
                    void hideAllPanels() { hidden++; }
                    void captureInteractionStackForPanel() {}
                    int getDesktopAreaWidth() { return width; }
                    int getDesktopAreaHeight() { return height; }
                    int getTaskbarHeight() { return bar; }
                    int getDesktopAreaLeft() { return left; }
                    int getDesktopAreaTop() { return top; }
                    String getString(int res) { return "label"; }
                    void setErrorStatus(String code, String message) { throw new AssertionError(message); }
                }
                static class Ui {
                    int density = 1;
                    int menuWidth(int width, int margin) { return Math.min(360 * density, Math.max(1, width - 2 * margin)); }
                }
                final Activity mActivity = new Activity();
                final Panel mPanel = new Panel();
                final Ui mUi = new Ui();
                int renders;
                int dp(int value) { return value * mUi.density; }
                void render() { renders++; }
                public static void verify() {
                    Fixture f = new Fixture();
                """ + scenario + "}\n" + RuntimeSourceFixture.methods("SystemPanelController", "toggle"));
    }
}
