package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;
import java.util.List;
import org.junit.Test;

public final class AppearanceSettingsTest {
    @Test public void pageUsesActivityInsetsAndInvalidatesEditsWhenLeaving() throws Exception {
        String create = RuntimeSourceFixture.methods("AppearanceSettings", "createPage");
        assertTrue(create.contains("UiToolLayout.page"));
        assertTrue(RuntimeSourceFixture.methods("UiToolLayout", "page").contains("SystemBarInsets.addToPadding(page, ime)"));
        assertTrue(create.contains("UiToolLayout.scroll(page, 640)"));
        assertFalse(create.contains("AlertDialog"));
        RuntimeSourceFixture.verify("""
                static class AppearanceStore {
                    static int listeners=1;
                    static void unlisten(Runnable listener) { listeners--; }
                }
                Object mPage=new Object();
                Object mSystemThemeChoice=new Object();
                Runnable mChanged=()->{};
                List<Runnable> mRefreshers=new ArrayList<>(List.of(()->{}));
                int generation, childClosures;
                void invalidateFiles() { generation++; }
                void dismissChildren() { childClosures++; }
                """ + RuntimeSourceFixture.methods("AppearanceSettings", "dismissPage", "isOpen") + """
                public static void verify() {
                    var page=new Fixture();
                    check(page.isOpen(), "page not open");
                    page.dismissPage();
                    check(!page.isOpen() && page.mRefreshers.isEmpty(), "page retained detached views");
                    check(page.mSystemThemeChoice==null, "page retained system theme control");
                    check(page.generation==1 && page.childClosures==1, "page retained pending edits or modals");
                    check(AppearanceStore.listeners==0, "page retained appearance subscription");
                }
                """);
    }

    @Test public void systemThemeKeepsIndependentStateAndAccessAvailability() throws Exception {
        String create = RuntimeSourceFixture.methods("AppearanceSettings", "createPage");
        assertTrue(create.indexOf("systemThemeControls(page)") < create.indexOf("R.string.appearance_scope"));
        assertFalse(RuntimeSourceFixture.methods("SettingsView", "create").contains("R.string.settings_system_theme"));
        String controls = RuntimeSourceFixture.methods("AppearanceSettings", "systemThemeControls");
        assertTrue(controls.contains("mSetSystemTheme.accept"));
        assertTrue(controls.contains("if (mSystemThemeAvailable)"));
        assertFalse(controls.contains("mWorkspaceKey"));
        assertTrue(RuntimeSourceFixture.methods("SettingsActivity", "setSystemTheme")
                .contains("saveSetting(MagicDeskSettings.setSystemTheme(theme))"));
        RuntimeSourceFixture.verify("""
                static class DesktopSystemThemeSession { enum Preference { UNCHANGED,LIGHT,DARK } }
                static class Spinner {
                    int selected; boolean enabled;
                    void setSelection(int index) { selected=index; }
                    void setEnabled(boolean value) { enabled=value; }
                }
                DesktopSystemThemeSession.Preference mSystemTheme;
                boolean mSystemThemeAvailable;
                Spinner mSystemThemeChoice;
                """ + RuntimeSourceFixture.methods("AppearanceSettings", "renderSystemTheme") + """
                public static void verify() {
                    var page=new Fixture();
                    page.renderSystemTheme(DesktopSystemThemeSession.Preference.LIGHT,false);
                    check(page.mSystemTheme==DesktopSystemThemeSession.Preference.LIGHT && !page.mSystemThemeAvailable,
                            "state before page creation was lost");
                    page.mSystemThemeChoice=new Spinner();
                    page.renderSystemTheme(DesktopSystemThemeSession.Preference.DARK,true);
                    check(page.mSystemThemeChoice.selected==2 && page.mSystemThemeChoice.enabled,
                            "saved preference or access grant not rendered");
                    page.renderSystemTheme(DesktopSystemThemeSession.Preference.UNCHANGED,false);
                    check(page.mSystemThemeChoice.selected==0 && !page.mSystemThemeChoice.enabled,
                            "access loss left system theme editable");
                }
                """);
    }

    @Test public void editingLocalTypographyDoesNotFreezeGlobalPanelsOrColors() throws Exception {
        var before = ShellAppearance.defaults();
        var after = before.withTypography(new ShellAppearance.Typography(ShellAppearance.Font.MONO, 1));
        String changed = AppearanceSettings.changedPatch("{\"motion\":{\"reduced\":true}}", before, after);
        var patch = new org.json.JSONObject(changed);
        assertFalse(patch.has("composition"));
        assertFalse(patch.has("colors"));
        assertTrue(patch.getJSONObject("motion").getBoolean("reduced"));
        assertEquals("mono", patch.getJSONObject("typography").getString("font"));
        assertFalse(patch.getJSONObject("typography").has("scale"));
        var resolved = WorkspaceAppearancePatch.parse(changed).resolve(ShellAppearance.preset("light"));
        assertEquals(ShellAppearance.preset("light").palette(), resolved.palette());
        assertEquals(ShellAppearance.Font.MONO, resolved.typography().font());
    }

    @Test public void editingCommonOpacityKeepsBlurAndUnchangedSectionsInherited() throws Exception {
        var before = ShellAppearance.defaults().withBackdrop(new ShellAppearance.Backdrop(.9f, 12));
        var after = before.withBackdrop(new ShellAppearance.Backdrop(.6f, 12));
        String changed = AppearanceSettings.changedPatch("{\"motion\":{\"reduced\":true}}", before, after);
        var patch = new org.json.JSONObject(changed);
        assertEquals(2, patch.length());
        assertTrue(patch.getJSONObject("motion").getBoolean("reduced"));
        assertEquals(1, patch.getJSONObject("backdrop").length());
        assertEquals(.6, patch.getJSONObject("backdrop").getDouble("opacity"), .0001);
        var global = ShellAppearance.preset("light").withBackdrop(new ShellAppearance.Backdrop(.8f, 32));
        var resolved = WorkspaceAppearancePatch.parse(changed).resolve(global);
        assertEquals(new ShellAppearance.Backdrop(.6f, 32), resolved.backdrop());
        assertEquals(global.palette(), resolved.palette());
        assertEquals(global.composition(), resolved.composition());
    }

    @Test public void editingCommonBlurKeepsOpacityInheritedIncludingWhenBlurIsOff() throws Exception {
        var before = ShellAppearance.defaults().withBackdrop(new ShellAppearance.Backdrop(.7f, 12));
        for (int radius : new int[] {0, 64}) {
            var after = before.withBackdrop(new ShellAppearance.Backdrop(.7f, radius));
            String changed = AppearanceSettings.changedPatch("{}", before, after);
            var patch = new org.json.JSONObject(changed);
            assertEquals(1, patch.length());
            assertEquals(1, patch.getJSONObject("backdrop").length());
            assertEquals(radius, patch.getJSONObject("backdrop").getInt("blurRadiusDp"));
            var global = before.withBackdrop(new ShellAppearance.Backdrop(.4f, 48));
            var resolved = WorkspaceAppearancePatch.parse(changed).resolve(global);
            assertEquals(new ShellAppearance.Backdrop(.4f, radius), resolved.backdrop());
        }
    }

    @Test public void panelBackdropChangesPreserveGeometryIdentityAndComponents() {
        var style = new ShellAppearance.PanelStyle(ShellAppearance.Width.CONTENT, ShellAppearance.Alignment.END,
                700, 9, 11, 72, 5, 17, null, false, ShellDockEffect.NONE);
        var original = new ShellPanel("dock", ShellPanel.Edge.LEFT, style,
                List.of(ShellComposition.Component.of(ShellComposition.Kind.START)));
        var backdrop = new ShellAppearance.Backdrop(.45f, 24);
        var changed = AppearanceSettings.withPanelBackdrop(original, backdrop);
        assertEquals(backdrop, changed.style().backdrop());
        assertEquals(original.id(), changed.id());
        assertEquals(original.edge(), changed.edge());
        assertEquals(original.components(), changed.components());
        assertEquals(original, AppearanceSettings.withPanelBackdrop(changed, null));
        assertNull(original.style().backdrop());
    }

    @Test public void explicitPanelBackdropDoesNotChangeCommonBackdropOrOtherPanels() throws Exception {
        var dock = panel("dock", ShellComposition.Kind.START);
        var status = panel("status", ShellComposition.Kind.CLOCK);
        var before = ShellAppearance.defaults().withBackdrop(new ShellAppearance.Backdrop(.7f, 12))
                .withComposition(new ShellComposition(List.of(dock, status), ShellComposition.Start.defaults()));
        var after = before.withComposition(new ShellComposition(List.of(
                AppearanceSettings.withPanelBackdrop(dock, before.backdrop()), status), before.composition().start()));
        String changed = AppearanceSettings.changedPatch("{}", before, after);
        var patch = new org.json.JSONObject(changed);
        assertEquals(1, patch.length());
        assertEquals(1, patch.getJSONObject("composition").length());
        var panels = patch.getJSONObject("composition").getJSONArray("panels");
        assertTrue(panels.getJSONObject(0).getJSONObject("style").has("backdrop"));
        assertFalse(panels.getJSONObject(1).getJSONObject("style").has("backdrop"));
        var global = before.withBackdrop(new ShellAppearance.Backdrop(.3f, 40));
        var resolved = WorkspaceAppearancePatch.parse(changed).resolve(global);
        assertEquals(global.backdrop(), resolved.backdrop());
        assertEquals(before.backdrop(), resolved.panelBackdrop("dock"));
        assertEquals(global.backdrop(), resolved.panelBackdrop("status"));
        assertEquals(status, resolved.composition().panel("status"));
    }

    @Test public void clearingPanelOverrideRemovesBackdropAndRestoresCommonInheritance() throws Exception {
        var dock = panel("dock", ShellComposition.Kind.START);
        var global = ShellAppearance.defaults().withBackdrop(new ShellAppearance.Backdrop(.8f, 8))
                .withComposition(new ShellComposition(List.of(dock), ShellComposition.Start.defaults()));
        var before = global.withBackdrop(new ShellAppearance.Backdrop(.8f, 16)).withComposition(new ShellComposition(
                List.of(AppearanceSettings.withPanelBackdrop(dock, new ShellAppearance.Backdrop(.5f, 24))),
                global.composition().start()));
        String previous = AppearanceSettings.changedPatch("{}", global, before);
        var after = before.withComposition(new ShellComposition(List.of(
                AppearanceSettings.withPanelBackdrop(before.composition().panel("dock"), null)), before.composition().start()));
        String changed = AppearanceSettings.changedPatch(previous, before, after);
        var patch = new org.json.JSONObject(changed);
        assertEquals(1, patch.getJSONObject("backdrop").length());
        assertEquals(16, patch.getJSONObject("backdrop").getInt("blurRadiusDp"));
        assertFalse(patch.getJSONObject("composition").getJSONArray("panels")
                .getJSONObject(0).getJSONObject("style").has("backdrop"));
        var resolved = WorkspaceAppearancePatch.parse(changed)
                .resolve(global.withBackdrop(new ShellAppearance.Backdrop(.35f, 32)));
        assertNull(resolved.composition().panel("dock").style().backdrop());
        assertEquals(new ShellAppearance.Backdrop(.35f, 16), resolved.panelBackdrop("dock"));
    }

    private static ShellPanel panel(String id, ShellComposition.Kind... kinds) {
        return new ShellPanel(id, ShellPanel.Edge.BOTTOM, ShellAppearance.PanelStyle.defaults(),
                java.util.Arrays.stream(kinds).map(ShellComposition.Component::of).toList());
    }

    @Test public void movingSingletonPreservesIdentityAndLeavesValidSource() {
        var original = new ShellComposition(List.of(panel("dock", ShellComposition.Kind.START),
                panel("status", ShellComposition.Kind.CLOCK)), ShellComposition.Start.defaults());
        var moved = AppearanceSettings.moveComponent(original, "dock", 0, "status");
        assertEquals("dock", moved.panels().get(0).id());
        assertEquals(ShellComposition.Kind.SPACER, moved.panels().get(0).components().get(0).type());
        assertEquals(List.of(ShellComposition.Kind.CLOCK, ShellComposition.Kind.START),
                moved.panels().get(1).components().stream().map(ShellComposition.Component::type).toList());
        assertEquals(ShellComposition.Kind.START, original.panels().get(0).components().get(0).type());
    }

    @Test public void fullOrMissingDestinationCannotLoseSourceComponent() {
        var full = new ShellPanel("full", ShellPanel.Edge.LEFT, ShellAppearance.PanelStyle.defaults(),
                java.util.Collections.nCopies(24, ShellComposition.Component.of(ShellComposition.Kind.SPACER)));
        var original = new ShellComposition(List.of(panel("dock", ShellComposition.Kind.START), full), ShellComposition.Start.defaults());
        assertThrows(IllegalArgumentException.class, () -> AppearanceSettings.moveComponent(original, "dock", 0, "full"));
        assertThrows(IllegalArgumentException.class, () -> AppearanceSettings.moveComponent(original, "dock", 0, "missing"));
        assertThrows(IllegalArgumentException.class, () -> AppearanceSettings.moveComponent(original, "dock", 0, "dock"));
        assertEquals(ShellComposition.Kind.START, original.panels().get(0).components().get(0).type());
    }

    @Test public void addingPanelNeverRenumbersExistingIds() {
        var panels = List.of(panel("panel-1", ShellComposition.Kind.START), panel("panel-3", ShellComposition.Kind.CLOCK));
        assertEquals("panel-2", AppearanceSettings.nextPanelId(panels));
        assertEquals("panel-3", panels.get(1).id());
    }
}
