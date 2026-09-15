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

    public static boolean save(final Profile profile) {
        if (profile == null || profile.key == null
                || profile.key.length() == 0) {
            return false;
        }
        final Profile snapshot = copy(profile);
        return DesktopStateStore.update(state ->
                state.displayProfiles.put(snapshot.key, snapshot));
    }

    static void setOutputTiming(
            final Profile profile,
            final String outputTiming) {
        final String normalizedTiming = outputTiming == null
                || outputTiming.isEmpty() ? null : outputTiming;
        if (normalizedTiming == null) {
            profile.resetOutputModePending |= profile.outputTiming != null;
        } else {
            profile.resetOutputModePending = false;
        }
        profile.outputTiming = normalizedTiming;
    }

    static Profile copy(final Profile source) {
        if (source == null) {
            return null;
        }
        final Profile copy = new Profile(source.key);
        copy.dpi = source.dpi;
        copy.dpiExplicit = source.dpiExplicit;
        copy.outputTiming = source.outputTiming;
        copy.resetOutputModePending = source.resetOutputModePending;
        copy.originProfileKey = source.originProfileKey;
        copy.width = source.width;
        copy.height = source.height;
        return copy;
    }

    public static final class Profile {
        public final String key;
        public int dpi;
        public boolean dpiExplicit;
        public String outputTiming;
        public boolean resetOutputModePending;
        public String originProfileKey = "";
        // Virtual creation size, not a physical output timing or a live display override.
        public int width;
        public int height;
        Profile(final String key) {
            this.key = key;
        }
    }
}
