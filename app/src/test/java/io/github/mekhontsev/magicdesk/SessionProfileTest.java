package io.github.mekhontsev.magicdesk;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

public final class SessionProfileTest {
    @Test
    public void missingValuesUseAutoDefaults() {
        final SessionProfile profile =
                SessionProfile.load(new MemoryStore());

        assertProfile(
                profile,
                SessionProfile.PrivilegeMode.AUTO,
                SessionProfile.DisplayTarget.AUTO);
    }

    @Test
    public void everyProfileRoundTripsThroughPreferences() {
        for (final SessionProfile.PrivilegeMode privilegeMode
                : SessionProfile.PrivilegeMode.values()) {
            for (final SessionProfile.DisplayTarget displayTarget
                    : SessionProfile.DisplayTarget.values()) {
                final MemoryStore store = new MemoryStore();
                new SessionProfile(privilegeMode, displayTarget).save(store);

                assertProfile(
                        SessionProfile.load(store),
                        privilegeMode,
                        displayTarget);
            }
        }
    }

    @Test
    public void malformedStoredValuesUseAutoDefaults() {
        final MemoryStore store = new MemoryStore();
        store.putStrings(
                "privilege_mode",
                "not-a-mode",
                "display_target",
                "");

        assertProfile(
                SessionProfile.load(store),
                SessionProfile.PrivilegeMode.AUTO,
                SessionProfile.DisplayTarget.AUTO);
    }

    @Test
    public void launchOverridesAreTrimmedAndCaseInsensitive() {
        final SessionProfile saved = new SessionProfile(
                SessionProfile.PrivilegeMode.BASIC,
                SessionProfile.DisplayTarget.PRIMARY);

        assertProfile(
                SessionProfile.withLaunchOverrides(
                        saved,
                        "  ShIzUkU ",
                        " ExTeRnAl "),
                SessionProfile.PrivilegeMode.SHIZUKU,
                SessionProfile.DisplayTarget.EXTERNAL);
    }

    @Test
    public void invalidLaunchOverridesPreserveSavedProfile() {
        final SessionProfile saved = new SessionProfile(
                SessionProfile.PrivilegeMode.ROOT,
                SessionProfile.DisplayTarget.CURRENT);

        assertProfile(
                SessionProfile.withLaunchOverrides(
                        saved,
                        "invalid",
                        null),
                SessionProfile.PrivilegeMode.ROOT,
                SessionProfile.DisplayTarget.CURRENT);
    }

    @Test
    public void nullProfileAndConstructorValuesUseAutoDefaults() {
        assertProfile(
                SessionProfile.withLaunchOverrides(null, null, null),
                SessionProfile.PrivilegeMode.AUTO,
                SessionProfile.DisplayTarget.AUTO);
        assertProfile(
                new SessionProfile(null, null),
                SessionProfile.PrivilegeMode.AUTO,
                SessionProfile.DisplayTarget.AUTO);
    }

    private static void assertProfile(
            final SessionProfile profile,
            final SessionProfile.PrivilegeMode privilegeMode,
            final SessionProfile.DisplayTarget displayTarget) {
        assertEquals(privilegeMode, profile.privilegeMode);
        assertEquals(displayTarget, profile.displayTarget);
    }

    private static final class MemoryStore
            implements SessionProfile.PreferenceStore {
        private final Map<String, String> mValues = new HashMap<>();

        @Override
        public String getString(final String key) {
            return mValues.get(key);
        }

        @Override
        public void putStrings(
                final String firstKey,
                final String firstValue,
                final String secondKey,
                final String secondValue) {
            mValues.put(firstKey, firstValue);
            mValues.put(secondKey, secondValue);
        }
    }
}
