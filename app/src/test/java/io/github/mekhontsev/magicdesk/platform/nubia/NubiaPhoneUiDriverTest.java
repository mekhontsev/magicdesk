package io.github.mekhontsev.magicdesk.platform.nubia;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NubiaPhoneUiDriverTest {
    private static final String SECONDARY_HOME_ACTIVITY =
            "com.android.launcher3.secondarydisplay.SecondaryDisplayLauncher";

    @Test
    public void recognizesNubiaSecondaryHomeLaunch() {
        assertTrue(NubiaPhoneUiDriver.isTransientSecondaryHome(
                true, SECONDARY_HOME_ACTIVITY));
    }

    @Test
    public void rejectsOrdinaryHomeAndUnrelatedActivities() {
        assertFalse(NubiaPhoneUiDriver.isTransientSecondaryHome(
                false, SECONDARY_HOME_ACTIVITY));
        assertFalse(NubiaPhoneUiDriver.isTransientSecondaryHome(
                true, "com.android.launcher3.Launcher"));
        assertFalse(NubiaPhoneUiDriver.isTransientSecondaryHome(
                true, null));
    }
}
