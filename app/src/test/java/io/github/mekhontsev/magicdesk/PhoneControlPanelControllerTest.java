package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class PhoneControlPanelControllerTest {
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
