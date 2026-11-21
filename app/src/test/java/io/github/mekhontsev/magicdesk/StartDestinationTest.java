package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class StartDestinationTest {
    @Test public void contextLaunchActionsKeepNewWindowLocalAndPreserveDestination() throws Exception {
        RuntimeSourceFixture.verify("""
                enum DesktopLaunchMode { AUTO, WINDOWED, FULLSCREEN }
                enum DesktopTaskInstancePolicy { CREATE_NEW, REUSE_EXISTING }
                static class DesktopLaunchPresentation {
                    DesktopLaunchMode mode;
                    DesktopTaskInstancePolicy instancePolicy = DesktopTaskInstancePolicy.REUSE_EXISTING;
                    static DesktopLaunchPresentation forMode(DesktopLaunchMode mode) {
                        var p = new DesktopLaunchPresentation(); p.mode = mode; return p;
                    }
                    DesktopLaunchPresentation withInstancePolicy(DesktopTaskInstancePolicy policy) {
                        var p = forMode(mode); p.instancePolicy = policy; return p;
                    }
                }
                static class StartDisplaySelector { record Target(int displayId, String uniqueId, String placement) { } }
                static class DesktopRuntimeBridge { static boolean desktop; static boolean hasWorkspace(int id) { return desktop; } }
                static class OrdinaryActivityLaunch { static boolean available; static boolean canLaunchWindowed() { return available; } }
                static class R { static class string {
                    static final int action_open = 1, action_open_fullscreen = 2, action_open_floating = 3,
                            start_mode_default = 4, start_mode_windowed = 5, start_mode_fullscreen = 6,
                            start_mode_independent = 7;
                } }
                static class ApplicationContextMenu {
                    Options options = new Options();
                    List<Item> items = new ArrayList<>();
                    void add(int label, Runnable action) { add(label, true, action); }
                    void add(int label, boolean enabled, Runnable action) { items.add(new Item(label, enabled, action)); }
                }
                record Item(int label, boolean enabled, Runnable action) { }
                public static void verify() {
                    var target = new StartDisplaySelector.Target(7, "exact-display", "display");
                    var selected = DesktopLaunchPresentation.forMode(DesktopLaunchMode.AUTO)
                            .withInstancePolicy(DesktopTaskInstancePolicy.CREATE_NEW);
                    var targets = new ArrayList<StartDisplaySelector.Target>();
                    var presentations = new ArrayList<DesktopLaunchPresentation>();
                    for (boolean desktop : new boolean[]{false, true}) for (boolean available : new boolean[]{false, true}) {
                        DesktopRuntimeBridge.desktop = desktop; OrdinaryActivityLaunch.available = available;
                        var menu = new ApplicationContextMenu();
                        check(!menu.options.newWindow, "new menu inherited a checked option");
                        addMenuItems(menu, target, selected, (t, p) -> { targets.add(t); presentations.add(p); });
                        check(menu.items.size() == 3, "inconsistent launch actions");
                        check(menu.items.get(0).label == R.string.action_open && menu.items.get(1).label == R.string.action_open_fullscreen
                                && menu.items.get(2).label == R.string.action_open_floating, "inconsistent order");
                        check(menu.items.get(2).enabled == (desktop || available), "independent policy bypassed");
                        for (Item item : menu.items) if (item.enabled) item.action.run();
                    }
                    check(presentations.get(0).mode == selected.mode && targets.get(0) == target, "default choices discarded");
                    check(presentations.get(1).mode == DesktopLaunchMode.FULLSCREEN && targets.get(1) == target,
                            "fullscreen discarded independent placement");
                    check(presentations.get(4).mode == DesktopLaunchMode.WINDOWED && targets.get(4).placement().equals("auto"),
                            "window did not select available ownership");
                    for (int i = 0; i < targets.size(); i++) {
                        check(targets.get(i).displayId() == 7 && targets.get(i).uniqueId().equals("exact-display"), "target changed");
                        check(presentations.get(i).instancePolicy == DesktopTaskInstancePolicy.REUSE_EXISTING,
                                "unchecked menu requested a new instance");
                    }
                    var menu = new ApplicationContextMenu();
                    addMenuItems(menu, target, selected, (t, p) -> presentations.add(p));
                    menu.options.newWindow = true;
                    for (Item item : menu.items) {
                        item.action.run();
                        check(presentations.get(presentations.size() - 1).instancePolicy == DesktopTaskInstancePolicy.CREATE_NEW,
                                "Open ignored menu option");
                    }
                    check(!new Options().newWindow, "menu option persisted");
                }
                """ + RuntimeSourceFixture.nestedClass("StartLaunchControls", "Mode")
                        + RuntimeSourceFixture.nestedClass("ApplicationContextMenu", "Options")
                        + RuntimeSourceFixture.methods("StartLaunchControls", "addMenuItems"));
        for (String name : new String[]{"StartMenuContent", "FullscreenStartController"}) {
            assertTrue(java.nio.file.Files.readString(java.nio.file.Path.of(RuntimeSourceFixture.MAIN + name + ".java"))
                    .contains("StartLaunchControls.addMenuItems("));
        }
    }

    @Test public void recentScopeChangesWithDestinationAndModeWithoutDependingOnHostOrAccess() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                enum DesktopLaunchMode { AUTO, WINDOWED, FULLSCREEN }
                static class OrdinaryActivityLaunch { static boolean canLaunchWindowed() { return false; } }
                static class R { static class string {
                    static final int start_mode_default = 1, start_mode_windowed = 2,
                            start_mode_fullscreen = 3, start_mode_independent = 4;
                } }
                record Target(int displayId) { }
                static class DisplaySelector { int display; Target target() { return new Target(display); } }
                static class Button { int text; void setText(int value) { text = value; } }
                static class DesktopRuntimeBridge {
                    static Set<Integer> desktops = Set.of(4, 7);
                    static Set<Integer> workspaceDisplayIds() { return desktops; }
                    static boolean hasWorkspace(int display) { return desktops.contains(display); }
                }
                DisplaySelector mDisplay = new DisplaySelector();
                Button mModeButton = new Button();
                Mode mMode = Mode.DEFAULT;
                public static void verify() {
                    Fixture f = new Fixture();
                    check(f.recentScope() == RecentLaunchScope.INDEPENDENT, "remote Desktop hijacked phone history");
                    f.mDisplay.display = 4;
                    check(f.recentScope() == RecentLaunchScope.DESKTOP, "selected Desktop history missing");
                    f.mMode = Mode.INDEPENDENT;
                    check(f.recentScope() == RecentLaunchScope.INDEPENDENT, "independent launch used managed history");
                    f.mMode = Mode.WINDOWED;
                    check(f.recentScope() == RecentLaunchScope.DESKTOP, "managed window history missing");
                    f.mDisplay.display = 7;
                    check(f.recentScope() == RecentLaunchScope.DESKTOP, "Desktop histories are not global");
                    DesktopRuntimeBridge.desktops = Set.of();
                    check(f.recentScope() == RecentLaunchScope.INDEPENDENT, "workspace loss broke read-only history");
                    f.refresh();
                    check(f.mMode == Mode.DEFAULT && f.mModeButton.text == R.string.start_mode_default,
                            "unavailable managed mode was not reset visibly");
                }
                """ + RuntimeSourceFixture.nestedClass("StartLaunchControls", "Mode")
                        + RuntimeSourceFixture.methods("StartLaunchControls", "recentScope", "refresh"),
                "ToolLaunchTarget", "RecentLaunchScope");
    }

    @Test public void modesSeparatePlacementFromWindowingAndUseIndependentCapability() throws Exception {
        RuntimeSourceFixture.verify("""
                enum DesktopLaunchMode { AUTO, WINDOWED, FULLSCREEN }
                static class OrdinaryActivityLaunch {
                    static boolean available;
                    static boolean canLaunchWindowed() { return available; }
                }
                static class R {
                    static class string {
                        static final int start_mode_default = 1, start_mode_windowed = 2,
                                start_mode_fullscreen = 3, start_mode_independent = 4;
                    }
                }
                public static void verify() {
                    check(Mode.DEFAULT.placement.equals("auto"), "default lost destination policy");
                    check(Mode.WINDOWED.placement.equals("auto"), "window requires Desktop");
                    check(Mode.FULLSCREEN.placement.equals("auto"), "fullscreen requires Desktop");
                    check(Mode.INDEPENDENT.placement.equals("display"), "independent claims Desktop");
                    check(Mode.INDEPENDENT.windowMode == DesktopLaunchMode.FULLSCREEN,
                            "independent task requests floating mode");
                    for (Mode mode : Mode.values()) { check(mode.available(true), "active Desktop choice missing"); }
                    check(Mode.DEFAULT.available(false) && Mode.INDEPENDENT.available(false),
                            "ordinary launches require Desktop");
                    check(!Mode.WINDOWED.available(false) && Mode.FULLSCREEN.available(false),
                            "unavailable freeform offered or ordinary fullscreen blocked");
                    OrdinaryActivityLaunch.available = true;
                    check(Mode.WINDOWED.available(false), "independent freeform missing");
                }
                """ + RuntimeSourceFixture.nestedClass("StartLaunchControls", "Mode"));
    }

    @Test public void destinationAndWindowModeAreExclusiveChoices() throws Exception {
        assertTrue(RuntimeSourceFixture.methods("StartDisplaySelector", "show")
                .contains("setGroupCheckable(0, true, true)"));
        final String controls = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/io/github/mekhontsev/magicdesk/StartLaunchControls.java"));
        assertTrue(controls.contains("setGroupCheckable(0, true, true)"));
        assertFalse(controls.contains("CheckBox"));
        assertFalse(controls.contains("start.new_window"));
        final String show = RuntimeSourceFixture.methods("ApplicationContextMenu", "show");
        assertTrue(show.indexOf("panel.addView(checkbox") < show.indexOf("ui.showMenuPopup"));
        assertTrue(show.contains("R.string.start_new_window), false"));
        assertTrue(show.contains("checkbox.setOnClickListener(view -> options.newWindow = checkbox.isChecked())"));
    }

    @Test public void sameNamedDisplaysHaveDistinctLabels() throws Exception {
        RuntimeSourceFixture.verify("""
                record DesktopDisplayInfo(int id, String name) { }
                public static void verify() {
                    check(displayLabel(new DesktopDisplayInfo(3, "MagicDesk")).equals("MagicDesk [3]"),
                            "first display has no visible identity");
                    check(displayLabel(new DesktopDisplayInfo(4, "MagicDesk")).equals("MagicDesk [4]"),
                            "same-named display is ambiguous");
                }
                """ + RuntimeSourceFixture.methods("StartDisplaySelector", "displayLabel"));
        final String show = RuntimeSourceFixture.methods("StartDisplaySelector", "show");
        final String selected = RuntimeSourceFixture.methods("StartDisplaySelector", "updateLabel");
        assertTrue(show.contains("displayLabel(display)"));
        assertTrue(selected.contains("mSelectedLabel"));
    }

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
                Target mSelected;
                String mSelectedLabel;
                static String displayLabel(DesktopDisplayInfo d) { return Integer.toString(d.id); }
                int labelUpdates;
                int selectionChanges;
                Runnable mChanged = () -> selectionChanges++;
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
                    check(phone.selectionChanges == 3, "destination changes did not refresh history");
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
        assertTrue(panel.contains("StartActivity.open(this, display)"));
        assertFalse(panel.contains("selectedDisplay"));
        final String selection = RuntimeSourceFixture.methods("StartDisplaySelector", "select");
        assertFalse(selection.contains("showSection"));
        assertFalse(selection.contains("setText"));
        assertFalse(selection.contains("DesktopOperations"));
    }
}
