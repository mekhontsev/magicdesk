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

        assertProfile(profile, SessionProfile.DisplayTarget.AUTO);
    }

    @Test
    public void everyProfileRoundTripsThroughPreferences() {
        for (final SessionProfile.DisplayTarget displayTarget
                : SessionProfile.DisplayTarget.values()) {
            final MemoryStore store = new MemoryStore();
            new SessionProfile(displayTarget).save(store);
            assertProfile(SessionProfile.load(store), displayTarget);
        }
    }

    @Test
    public void malformedStoredValuesUseAutoDefaults() {
        final MemoryStore store = new MemoryStore();
        store.putString("display_target", "");

        assertProfile(SessionProfile.load(store),
                SessionProfile.DisplayTarget.AUTO);
    }

    @Test
    public void launchOverridesAreTrimmedAndCaseInsensitive() {
        final SessionProfile saved = new SessionProfile(
                SessionProfile.DisplayTarget.PRIMARY);

        assertProfile(
                SessionProfile.withLaunchOverrides(
                        saved,
                        " ExTeRnAl "),
                SessionProfile.DisplayTarget.EXTERNAL);
    }

    @Test
    public void invalidLaunchOverridesPreserveSavedProfile() {
        final SessionProfile saved = new SessionProfile(
                SessionProfile.DisplayTarget.CURRENT);

        assertProfile(
                SessionProfile.withLaunchOverrides(
                        saved,
                        null),
                SessionProfile.DisplayTarget.CURRENT);
    }

    @Test
    public void nullProfileAndConstructorValuesUseAutoDefaults() {
        assertProfile(
                SessionProfile.withLaunchOverrides(null, null),
                SessionProfile.DisplayTarget.AUTO);
        assertProfile(
                new SessionProfile(null),
                SessionProfile.DisplayTarget.AUTO);
    }

    private static void assertProfile(
            final SessionProfile profile,
            final SessionProfile.DisplayTarget displayTarget) {
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
        public void putString(final String key, final String value) {
            mValues.put(key, value);
        }
    }
}
