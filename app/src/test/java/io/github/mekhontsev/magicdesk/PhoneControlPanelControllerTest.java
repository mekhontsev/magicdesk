package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PhoneControlPanelControllerTest {
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
    public void outputControlsRemainVisibleButReadOnlyDuringAnyDesktopSession() {
        final DesktopDisplayInfo wired = display(3, "wired", true, false);
        final PlatformProjectionDriver.Mode mode = new PlatformProjectionDriver.Mode("1920x1080@60", "1080p 60 Hz");
        final PlatformProjectionDriver.ModeSelection selection = new PlatformProjectionDriver.ModeSelection(
                mode, mode, mode, java.util.List.of(mode), true);
        assertTrue(DisplaySelectionView.hasOutputControls(wired, true));
        assertTrue(DisplaySelectionView.canConfigureOutput(wired, true, selection, -1, true, false));
        for (final int activeId : new int[] {0, 3, 8}) {
            assertFalse(DisplaySelectionView.canConfigureOutput(wired, true, selection, activeId, true, false));
        }
        assertFalse(DisplaySelectionView.canConfigureOutput(wired, true, selection, -1, true, true));
        assertFalse(DisplaySelectionView.canConfigureOutput(wired, true, selection, -1, false, false));
        assertFalse(DisplaySelectionView.canConfigureOutput(wired, false, selection, -1, true, false));
        assertFalse(DisplaySelectionView.canConfigureOutput(wired, true, null, -1, true, false));
        assertFalse(DisplaySelectionView.canConfigureOutput(display(0, "phone", true, false),
                true, selection, -1, true, false));
    }

    @Test
    public void outputControlsRequireSelectableModesOrASystemDefault() {
        final DesktopDisplayInfo wired = display(3, "wired", true, false);
        final PlatformProjectionDriver.ModeSelection empty = new PlatformProjectionDriver.ModeSelection(
                null, null, null, java.util.List.of(), true);
        assertFalse(DisplaySelectionView.canConfigureOutput(wired, true, empty, -1, true, false));
        final PlatformProjectionDriver.ModeSelection systemDefault = new PlatformProjectionDriver.ModeSelection(
                null, null, null, java.util.List.of(), true, true, true);
        assertTrue(DisplaySelectionView.canConfigureOutput(wired, true, systemDefault, -1, true, false));
        final PlatformProjectionDriver.ModeSelection readOnly = new PlatformProjectionDriver.ModeSelection(
                null, null, null, java.util.List.of(), false, true, true);
        assertFalse(DisplaySelectionView.canConfigureOutput(wired, true, readOnly, -1, true, false));
    }

    @Test
    public void desktopStatusNamesTheActiveScreenIncludingPhone() {
        final DesktopDisplayInfo[] displays = {display(0, "phone", true, false), display(5, "virtual", true, true)};
        assertEquals("Display [0]", PhoneControlPanelController.desktopDisplayLabel(displays, 0));
        assertEquals("Display [5]", PhoneControlPanelController.desktopDisplayLabel(displays, 5));
        assertEquals("8", PhoneControlPanelController.desktopDisplayLabel(displays, 8));
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
    public void displaySelectionAllowsOnlyOneSession() {
        final DesktopDisplayInfo phone = display(0, "phone", true, false);
        final DesktopDisplayInfo external = display(5, "virtual", true, true);
        assertTrue(DisplaySelectionView.canStart(phone, -1, true, false, 35));
        assertTrue(DisplaySelectionView.canStart(phone, 0, true, false, 35));
        assertFalse(DisplaySelectionView.canStart(phone, 5, true, false, 35));
        assertTrue(DisplaySelectionView.canStart(external, -1, true, false, 35));
        assertTrue(DisplaySelectionView.canStart(external, 5, true, false, 35));
        assertFalse(DisplaySelectionView.canStart(external, 0, true, false, 35));
        assertFalse(DisplaySelectionView.canStart(external, -1, false, false, 35));
        assertFalse(DisplaySelectionView.canStart(external, -1, true, true, 35));
        assertFalse(DisplaySelectionView.canStart(null, -1, true, false, 35));
        assertFalse(DisplaySelectionView.canStart(
                display(6, "internal", false, false), -1, true, false, 35));
        assertFalse(DisplaySelectionView.canStart(phone, -1, true, false, 34));
        assertFalse(DisplaySelectionView.canStart(external, -1, true, false, 34));
    }

    static DesktopDisplayInfo display(final int id, final String source,
            final boolean supported, final boolean owned) {
        return new DesktopDisplayInfo(id, "display:" + id, "Display", source,
                1920, 1080, 160, supported, owned);
    }
}
