package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import io.github.mekhontsev.magicdesk.platform.android.GenericAndroidPlatformDriver;

public final class DesktopCompatibilityPolicyTest {
    @Test
    public void androidDefaultsEnableOnlyFocusRepairAndRespectExplicitDisable() throws Exception {
        final PlatformFeatures defaults = new GenericAndroidPlatformDriver().features();
        final MagicDeskSettings.Values preferences = MagicDeskSettings.Values.defaults();
        final DesktopCompatibilityPolicy selected = preferences.compatibilityPolicy(defaults);
        for (final DesktopCompatibilityPolicy.Option option : DesktopCompatibilityPolicy.Option.values()) {
            assertEquals(option == DesktopCompatibilityPolicy.Option.FOCUS_REPAIR,
                    selected.enabled(option));
        }

        preferences.compatibility.put(DesktopCompatibilityPolicy.Option.FOCUS_REPAIR, false);
        final MagicDeskSettings.Values restored = MagicDeskSettings.Values.fromJson(preferences.toJson());
        assertFalse(restored.compatibilityPolicy(defaults).enabled(
                DesktopCompatibilityPolicy.Option.FOCUS_REPAIR));
        assertTrue(selected.enabled(DesktopCompatibilityPolicy.Option.FOCUS_REPAIR));
    }

    @Test
    public void unsetOptionsFollowDefaultsAndOverridesWorkInBothDirections() throws Exception {
        for (final DesktopCompatibilityPolicy.Option option : DesktopCompatibilityPolicy.Option.values()) {
            final PlatformFeatures enabled = features(DesktopCompatibilityPolicy.NONE.with(option, true));
            final PlatformFeatures disabled = features(DesktopCompatibilityPolicy.NONE);
            final MagicDeskSettings.Values preferences = MagicDeskSettings.Values.defaults();
            assertTrue(preferences.compatibilityPolicy(enabled).enabled(option));
            assertFalse(preferences.compatibilityPolicy(disabled).enabled(option));

            preferences.compatibility.put(option, false);
            final MagicDeskSettings.Values copy = preferences.copy();
            assertFalse(copy.compatibilityPolicy(enabled).enabled(option));
            preferences.compatibility.put(option, true);
            assertFalse(copy.compatibilityPolicy(enabled).enabled(option));
            final MagicDeskSettings.Values restored = MagicDeskSettings.Values.fromJson(preferences.toJson());
            assertTrue(restored.compatibilityPolicy(disabled).enabled(option));
            assertEquals(1, restored.compatibility.size());
            assertFalse(MagicDeskSettings.Values.fromJson(copy.toJson())
                    .compatibilityPolicy(enabled).enabled(option));
        }
    }

    @Test
    public void sessionSelectionDoesNotFollowSubsequentPreferenceChanges() {
        final MagicDeskSettings.Values preferences = MagicDeskSettings.Values.defaults();
        final DesktopCompatibilityPolicy current = preferences.compatibilityPolicy(
                features(DesktopCompatibilityPolicy.NONE));
        for (final DesktopCompatibilityPolicy.Option option : DesktopCompatibilityPolicy.Option.values()) {
            preferences.compatibility.put(option, true);
            assertFalse(current.enabled(option));
            assertTrue(preferences.compatibilityPolicy(features(current)).enabled(option));
        }
    }

    @Test
    public void malformedOverridesDoNotDisablePlatformDefaults() throws Exception {
        final MagicDeskSettings.Values preferences = MagicDeskSettings.Values.fromJson(
                new JSONObject("{\"compatibility\":{\"focusRepair\":\"false\",\"captionRefresh\":null}}"));
        assertTrue(preferences.compatibility.isEmpty());
        assertTrue(preferences.compatibilityPolicy(features(DesktopCompatibilityPolicy.NONE.with(
                DesktopCompatibilityPolicy.Option.FOCUS_REPAIR, true)))
                .enabled(DesktopCompatibilityPolicy.Option.FOCUS_REPAIR));
    }

    @Test
    public void transportAndPersistencePreserveEachIndependentBit() {
        DesktopCompatibilityPolicy selected = DesktopCompatibilityPolicy.NONE;
        for (final DesktopCompatibilityPolicy.Option option : DesktopCompatibilityPolicy.Option.values()) {
            selected = selected.with(option, true);
            assertEquals(selected.toString(), DesktopCompatibilityPolicy.fromBits(selected.bits()).toString());
            assertFalse(DesktopCompatibilityPolicy.NONE.enabled(option));
            assertFalse(selected.with(option, false).enabled(option));
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownTransportBitsAreRejected() {
        DesktopCompatibilityPolicy.fromBits(1 << 20);
    }

    private static PlatformFeatures features(final DesktopCompatibilityPolicy policy) {
        return new PlatformFeatures(true, true, policy, false);
    }
}
