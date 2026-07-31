package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class NubiaCaptionVisibilityManagerTest {
    @Test
    public void missingOrEnabledSettingUsesNubiaDefaultPrivacy() {
        assertTrue(NubiaCaptionVisibilityManager.parseWiredPrivacyMode(null));
        assertTrue(NubiaCaptionVisibilityManager.parseWiredPrivacyMode(""));
        assertTrue(NubiaCaptionVisibilityManager.parseWiredPrivacyMode("true"));
        assertTrue(NubiaCaptionVisibilityManager.parseWiredPrivacyMode(" TRUE "));
    }

    @Test
    public void explicitFalseRestoresVisibleExternalLayers() {
        assertFalse(NubiaCaptionVisibilityManager.parseWiredPrivacyMode("false"));
        assertFalse(NubiaCaptionVisibilityManager.parseWiredPrivacyMode(" FALSE "));
    }
}
