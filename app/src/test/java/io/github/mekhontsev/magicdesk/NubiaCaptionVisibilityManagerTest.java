package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public final class NubiaCaptionVisibilityManagerTest {
    @Test
    public void parsesEnabledPrivacyValuesFromBothProviderFormats() {
        assertEquals(Integer.valueOf(1),
                NubiaCaptionVisibilityManager.parsePrivacyValue("on"));
        assertEquals(Integer.valueOf(1),
                NubiaCaptionVisibilityManager.parsePrivacyValue("turn_on"));
        assertEquals(Integer.valueOf(1),
                NubiaCaptionVisibilityManager.parsePrivacyValue(" TRUE "));
    }

    @Test
    public void parsesDisabledPrivacyValuesFromBothProviderFormats() {
        assertEquals(Integer.valueOf(0),
                NubiaCaptionVisibilityManager.parsePrivacyValue("off"));
        assertEquals(Integer.valueOf(0),
                NubiaCaptionVisibilityManager.parsePrivacyValue("turn_off"));
        assertEquals(Integer.valueOf(0),
                NubiaCaptionVisibilityManager.parsePrivacyValue(" FALSE "));
    }

    @Test
    public void rejectsMissingOrUnknownPrivacyValues() {
        assertNull(NubiaCaptionVisibilityManager.parsePrivacyValue(null));
        assertNull(NubiaCaptionVisibilityManager.parsePrivacyValue(""));
        assertNull(NubiaCaptionVisibilityManager.parsePrivacyValue("unknown"));
    }
}
