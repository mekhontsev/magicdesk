package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PhoneControlPanelControllerTest {
    @Test
    public void contentWidthFollowsParentMeasurementAfterResize() throws Exception {
        RuntimeSourceFixture.verify("""
                static class Gravity { static int TOP = 1, CENTER_HORIZONTAL = 2; }
                static class View {
                    FrameLayout.LayoutParams params;
                    FrameLayout.LayoutParams getLayoutParams() { return params; }
                    static class MeasureSpec {
                        static final int UNSPECIFIED = 0, EXACTLY = 1, AT_MOST = 2;
                        static int makeMeasureSpec(int size, int mode) { return (size << 2) | mode; }
                        static int getMode(int spec) { return spec & 3; }
                        static int getSize(int spec) { return spec >>> 2; }
                    }
                }
                static class FrameLayout extends View {
                    View child;
                    int measuredChildWidth;
                    FrameLayout(Object activity) { }
                    static class LayoutParams {
                        static final int MATCH_PARENT = -1, WRAP_CONTENT = -2;
                        int width, height, gravity;
                        LayoutParams(int w, int h, int g) { width = w; height = h; gravity = g; }
                    }
                    void addView(View view, LayoutParams params) { child = view; view.params = params; }
                    protected void onMeasure(int width, int height) { measuredChildWidth = child.params.width; }
                }
                Object mActivity = new Object();
                float density = 3.25f;
                int dp(int value) { return Math.round(value * density); }
                public static void verify() {
                    Fixture f = new Fixture();
                    View content = new View();
                    FrameLayout host = (FrameLayout) f.centered(content);
                    host.onMeasure(View.MeasureSpec.makeMeasureSpec(796, View.MeasureSpec.EXACTLY), 0);
                    check(host.measuredChildWidth == 796, "initial narrow window");
                    host.onMeasure(View.MeasureSpec.makeMeasureSpec(1098, View.MeasureSpec.EXACTLY), 0);
                    check(host.measuredChildWidth == 1098, "fullscreen retained narrow content");
                    host.onMeasure(View.MeasureSpec.makeMeasureSpec(3000, View.MeasureSpec.EXACTLY), 0);
                    check(host.measuredChildWidth == f.dp(540), "large window lost maximum width");
                    host.onMeasure(View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST), 0);
                    check(host.measuredChildWidth == 600, "shrinking window exceeds parent");
                    f.density = 1;
                    host.onMeasure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), 0);
                    check(host.measuredChildWidth == 540, "density change retained old cap");
                    host.onMeasure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), 0);
                    check(host.measuredChildWidth == 540, "unbounded measurement collapsed content");
                    check(content.params.gravity == (Gravity.TOP | Gravity.CENTER_HORIZONTAL), "not centered");
                }
                """ + RuntimeSourceFixture.methods("PhoneControlPanelController", "centered"));
    }

    @Test
    public void appsSharesTheStatusRowInsteadOfTakingADisplayActionRow() throws Exception {
        final String status = RuntimeSourceFixture.methods("PhoneControlPanelController", "addStatus");
        assertTrue(status.contains("row.setOrientation(LinearLayout.HORIZONTAL)"));
        assertTrue(status.contains("status.addView(mStatus)"));
        assertTrue(status.contains("status.addView(mRuntime, runtimeParams)"));
        assertTrue(status.contains("row.addView(mApplications, appsParams)"));
        assertTrue(status.contains("mActions.openApplications()"));
        assertFalse(RuntimeSourceFixture.methods("PhoneControlPanelController", "addDesktopActions")
                .contains("mApplications"));
    }

    @Test
    public void displayChoicesUseDescendingIdsWithoutChangingTheSharedCatalog() {
        final DesktopDisplayInfo phone = display(0, "phone", true, false);
        final DesktopDisplayInfo wired = display(3, "wired", true, false);
        final DesktopDisplayInfo virtual = display(8, "virtual", true, true);
        final DesktopDisplayInfo[] catalog = {wired, phone, virtual};
        assertArrayEquals(new DesktopDisplayInfo[] {virtual, wired, phone},
                DisplaySelectionView.orderedDisplays(catalog));
        assertArrayEquals(new DesktopDisplayInfo[] {wired, phone, virtual}, catalog);
        assertArrayEquals(new DesktopDisplayInfo[0],
                DisplaySelectionView.orderedDisplays(new DesktopDisplayInfo[0]));
        assertArrayEquals(new DesktopDisplayInfo[] {phone},
                DisplaySelectionView.orderedDisplays(new DesktopDisplayInfo[] {phone}));
    }

    @Test
    public void outputControlsBelongOnlyToTheSelectedWiredDisplay() {
        assertTrue(DisplaySelectionView.hasOutputControls(display(3, "wired", true, false), true));
        assertFalse(DisplaySelectionView.hasOutputControls(display(3, "wired", true, false), false));
        assertFalse(DisplaySelectionView.hasOutputControls(display(0, "phone", true, false), true));
        assertFalse(DisplaySelectionView.hasOutputControls(display(4, "wireless", true, false), true));
        assertFalse(DisplaySelectionView.hasOutputControls(display(8, "virtual", true, true), true));
        assertFalse(DisplaySelectionView.hasOutputControls(null, true));
    }

    @Test
    public void outputButtonHasAStableSlotInTheDisplayActionGrid() throws Exception {
        final String source = java.nio.file.Files.readString(java.nio.file.Path.of(
                "src/main/java/io/github/mekhontsev/magicdesk/DisplaySelectionView.java"));
        assertFalse(source.contains("mOutput.setVisibility"));
        assertFalse(source.contains("mOutputOptions"));
        final String actions = RuntimeSourceFixture.methods("PhoneControlPanelController", "addDesktopActions");
        assertTrue(actions.contains("addGridAction(sessionActions, mDisplaySelection.outputControl())"));
        assertFalse(actions.contains("addControlSection"));
        final String dialog = RuntimeSourceFixture.methods("DisplaySelectionView", "showOutputModeDialog");
        assertTrue(dialog.contains("selection.current.displayLabel"));
        assertTrue(dialog.contains("mode.setEnabled(mCanConfigureOutput)"));
        assertFalse(dialog.contains("setOnItemSelectedListener"));
    }

    @Test
    public void outputDialogSelectsThePreferenceWithoutMistakingItForTheCurrentMode() {
        final var current = new PlatformProjectionDriver.Mode("1920x1080@60", "1080p 60 Hz");
        final var preferred = new PlatformProjectionDriver.Mode("1920x1080@120", "1080p 120 Hz");
        final var modes = java.util.List.of(new PlatformProjectionDriver.Mode("", "System / native"),
                current, preferred);
        final var selection = new PlatformProjectionDriver.ModeSelection(current, preferred, current,
                modes.subList(1, 3), true, true, false);
        assertEquals(2, DisplaySelectionView.outputModeIndex(selection, modes));
        assertEquals(0, DisplaySelectionView.outputModeIndex(selection.withPreferredTiming(null), modes));
        assertEquals(-1, DisplaySelectionView.outputModeIndex(selection, java.util.List.of(current)));
        assertEquals(-1, DisplaySelectionView.outputModeIndex(selection, java.util.List.of()));
        assertEquals(-1, DisplaySelectionView.outputModeIndex(new PlatformProjectionDriver.ModeSelection(
                current, null, null, java.util.List.of(current), false), modes));
    }

    @Test
    public void outputControlsLockOnlyTheSelectedDesktop() {
        final DesktopDisplayInfo wired = display(3, "wired", true, false);
        final PlatformProjectionDriver.Mode mode = new PlatformProjectionDriver.Mode("1920x1080@60", "1080p 60 Hz");
        final PlatformProjectionDriver.ModeSelection selection = new PlatformProjectionDriver.ModeSelection(
                mode, mode, mode, java.util.List.of(mode), true);
        assertTrue(DisplaySelectionView.hasOutputControls(wired, true));
        assertTrue(DisplaySelectionView.canConfigureOutput(wired, true, selection, java.util.Set.of(), true, false));
        for (final int activeId : new int[] {0, 3, 8}) {
            assertEquals(activeId != 3, DisplaySelectionView.canConfigureOutput(wired, true, selection, java.util.Set.of(activeId), true, false));
        }
        assertFalse(DisplaySelectionView.canConfigureOutput(wired, true, selection, java.util.Set.of(), true, true));
        assertFalse(DisplaySelectionView.canConfigureOutput(wired, true, selection, java.util.Set.of(), false, false));
        assertFalse(DisplaySelectionView.canConfigureOutput(wired, false, selection, java.util.Set.of(), true, false));
        assertFalse(DisplaySelectionView.canConfigureOutput(wired, true, null, java.util.Set.of(), true, false));
        assertFalse(DisplaySelectionView.canConfigureOutput(display(0, "phone", true, false),
                true, selection, java.util.Set.of(), true, false));
    }

    @Test
    public void outputControlsRequireSelectableModesOrASystemDefault() {
        final DesktopDisplayInfo wired = display(3, "wired", true, false);
        final PlatformProjectionDriver.ModeSelection empty = new PlatformProjectionDriver.ModeSelection(
                null, null, null, java.util.List.of(), true);
        assertFalse(DisplaySelectionView.canConfigureOutput(wired, true, empty, java.util.Set.of(), true, false));
        final PlatformProjectionDriver.ModeSelection systemDefault = new PlatformProjectionDriver.ModeSelection(
                null, null, null, java.util.List.of(), true, true, true);
        assertTrue(DisplaySelectionView.canConfigureOutput(wired, true, systemDefault, java.util.Set.of(), true, false));
        final PlatformProjectionDriver.ModeSelection readOnly = new PlatformProjectionDriver.ModeSelection(
                null, null, null, java.util.List.of(), false, true, true);
        assertFalse(DisplaySelectionView.canConfigureOutput(wired, true, readOnly, java.util.Set.of(), true, false));
    }

    @Test
    public void closeDesktopRequiresReadySession() {
        assertTrue(PhoneControlPanelController.canCloseDesktop(
                true, true, false));
        assertFalse(PhoneControlPanelController.canCloseDesktop(
                true, false, false));
        assertFalse(PhoneControlPanelController.canCloseDesktop(
                false, true, false));
        assertFalse(PhoneControlPanelController.canCloseDesktop(
                true, true, true));
    }

    @Test
    public void displaySelectionDoesNotDependOnOtherWorkspaces() {
        final DesktopDisplayInfo phone = display(0, "phone", true, false);
        final DesktopDisplayInfo external = display(5, "virtual", true, true);
        assertTrue(DisplaySelectionView.canStart(phone, true, false, 35));
        assertTrue(DisplaySelectionView.canStart(phone, true, false, 35));
        assertTrue(DisplaySelectionView.canStart(phone, true, false, 35));
        assertTrue(DisplaySelectionView.canStart(external, true, false, 35));
        assertTrue(DisplaySelectionView.canStart(external, true, false, 35));
        assertTrue(DisplaySelectionView.canStart(external, true, false, 35));
        assertFalse(DisplaySelectionView.canStart(external, false, false, 35));
        assertFalse(DisplaySelectionView.canStart(external, true, true, 35));
        assertFalse(DisplaySelectionView.canStart(null, true, false, 35));
        assertFalse(DisplaySelectionView.canStart(
                display(6, "internal", false, false), true, false, 35));
        assertFalse(DisplaySelectionView.canStart(phone, true, false, 34));
        assertFalse(DisplaySelectionView.canStart(external, true, false, 34));
    }

    static DesktopDisplayInfo display(final int id, final String source,
            final boolean supported, final boolean owned) {
        return new DesktopDisplayInfo(id, "display:" + id, "Display", source,
                1920, 1080, 160, supported, owned);
    }
}
