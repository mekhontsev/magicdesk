package io.github.mekhontsev.magicdesk;

public final class DisplayProfileStore {
    private DisplayProfileStore() {
    }

    static Profile load(final String key,
            final int defaultDpi) {
        final Profile stored = DesktopStateStore.read(state ->
                copy(state.displayProfiles.get(key)), null);
        if (stored != null) {
            return stored;
        }

        final Profile profile = new Profile(key);
        profile.dpi = defaultDpi;
        profile.dpiExplicit = false;
        return profile;
    }

    static boolean save(final Profile profile) {
        if (profile == null || profile.key == null
                || profile.key.length() == 0) {
            return false;
        }
        final Profile snapshot = copy(profile);
        return DesktopStateStore.update(state ->
                state.displayProfiles.put(snapshot.key, snapshot));
    }

    static Profile copy(final Profile source) {
        if (source == null) {
            return null;
        }
        final Profile copy = new Profile(source.key);
        copy.dpi = source.dpi;
        copy.dpiExplicit = source.dpiExplicit;
        copy.fillDisplay = source.fillDisplay;
        copy.outputTiming = source.outputTiming;
        return copy;
    }

    public static final class Profile {
        public final String key;
        public int dpi;
        public boolean dpiExplicit;
        public boolean fillDisplay = true;
        public String outputTiming;
        Profile(final String key) {
            this.key = key;
        }
    }
}
