package io.github.mekhontsev.magicdesk;

import org.junit.Test;
import static org.junit.Assert.*;

public final class StartEntryAppearanceTest {
    @Test public void borderIndicatesOnlySelectionOrKeyboardFocus() throws Exception {
        RuntimeSourceFixture.verify("io.github.mekhontsev.magicdesk", """
                static class android { static class animation { static class ValueAnimator {
                    static boolean areAnimatorsEnabled() { return true; }
                } } static class R { static class attr {
                    static final int state_selected=1, state_focused=2, state_pressed=3, state_enabled=4, state_hovered=5;
                } } }
                static class Context {}
                static class AppearanceScopeSource {
                    AppearanceScopeSource(Context context) {}
                    ShellAppearance current() { return AppearanceStore.current(); }
                }
                static class AppearanceStore { static ShellAppearance current() { return ShellAppearance.defaults(); } }
                static class GradientDrawable {
                    int fill, stroke, border; float radius;
                    void setColor(int color) { fill = color; }
                    void setCornerRadius(float value) { radius = value; }
                    void setStroke(int width, int color) { stroke = width; border = color; }
                }
                static class StateListDrawable {
                    final Map<Integer, GradientDrawable> states = new LinkedHashMap<>();
                    void addState(int[] state, GradientDrawable background) { states.put(state.length == 0 ? 0 : state[0], background); }
                    void setEnterFadeDuration(int ms) {} void setExitFadeDuration(int ms) {} void invalidateSelf() {}
                    protected boolean onStateChange(int[] states) { return true; }
                }
                public static void verify() {
                    for (int radius : new int[] {7, 12}) {
                        var states = new UiFeedbackDrawable(null, 1f, radius).states;
                        check(states.size() == 6, "missing interactive state");
                        int accent = AppearanceStore.current().palette().color(UiColor.ACCENT);
                        for (int state : new int[] {1, 2}) check(states.get(state).stroke == 1 && states.get(state).border == accent, "focus/selection lost outline");
                        for (int state : new int[] {0, 3, 5, -4}) check(states.get(state).stroke == 0, "permanent outline");
                        check(states.get(0).fill == 0, "idle button paints its own background");
                        check(states.values().stream().allMatch(value -> value.radius == radius), "state changes geometry");
                    }
                }
                """ + RuntimeSourceFixture.nestedClass("UiFeedbackDrawable", "UiFeedbackDrawable")
                        .replace("final class UiFeedbackDrawable", "static final class UiFeedbackDrawable"),
                "ShellAppearance", "ShellControls", "ShellComposition", "ShellPanel", "ShellMotion", "ShellResources", "UiColor");
    }
    @Test public void gridAndSearchShareAppearanceIndependentOfLaunchBackend() throws Exception {
        final String tile = RuntimeSourceFixture.methods("StartMenuContent", "createAppTile");
        assertTrue(tile.contains("UiAppearance.component(tile, ShellControls.Role.APP_TILE)"));
        assertFalse(tile.contains("canFloat"));
        final String row = RuntimeSourceFixture.methods("StartMenuContent", "createSearchRow");
        assertTrue(row.contains("UiAppearance.component(row, ShellControls.Role.APP_TILE)"));
        assertTrue(row.contains("row.setSelected(selected)"));
        assertTrue(tile.contains("UiAppearance.componentText(label, ShellControls.Role.APP_TILE)"));
        assertTrue(row.contains("UiAppearance.componentText(name, ShellControls.Role.APP_TILE)"));
    }
}
