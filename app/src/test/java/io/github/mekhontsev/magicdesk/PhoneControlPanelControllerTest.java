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
    public void openDesktopHereOnlyStartsOrPresentsPhoneDesktop() {
        assertTrue(PhoneControlPanelController.canOpenDesktopHere(
                false, false, true, false));
        assertTrue(PhoneControlPanelController.canOpenDesktopHere(
                true, false, true, false));
        assertFalse(PhoneControlPanelController.canOpenDesktopHere(
                true, true, true, false));
        assertFalse(PhoneControlPanelController.canOpenDesktopHere(
                false, false, false, false));
        assertFalse(PhoneControlPanelController.canOpenDesktopHere(
                false, false, true, true));
    }

    @Test
    public void externalDesktopOnlyStartsOrPresentsExternalSession() {
        assertTrue(PhoneControlPanelController.canOpenExternalDesktop(
                false, false, true, true, true, false));
        assertTrue(PhoneControlPanelController.canOpenExternalDesktop(
                true, true, true, true, true, false));
        assertFalse(PhoneControlPanelController.canOpenExternalDesktop(
                true, false, true, true, true, false));
        assertFalse(PhoneControlPanelController.canOpenExternalDesktop(
                false, false, false, true, true, false));
        assertFalse(PhoneControlPanelController.canOpenExternalDesktop(
                false, false, true, false, true, false));
        assertFalse(PhoneControlPanelController.canOpenExternalDesktop(
                false, false, true, true, false, false));
        assertFalse(PhoneControlPanelController.canOpenExternalDesktop(
                false, false, true, true, true, true));
    }
}
