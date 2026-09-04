package io.github.mekhontsev.magicdesk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persists explicit per-application presentation profiles. */
final class AppPresentationProfileStore {
    private AppPresentationProfileStore() {
    }

    static AppPresentationProfile load(final String packageName) {
        if (!isUserPackage(packageName)) {
            return null;
        }
        return DesktopStateStore.read(
                state -> copy(state.appPresentations.get(packageName)),
                null);
    }

    static Map<String, AppPresentationProfile> loadAll() {
        return DesktopStateStore.read(state -> {
            final Map<String, AppPresentationProfile> profiles =
                    new LinkedHashMap<>();
            for (final Map.Entry<String, AppPresentationProfile> entry
                    : state.appPresentations.entrySet()) {
                final AppPresentationProfile profile = copy(entry.getValue());
                if (isUserPackage(entry.getKey()) && profile != null) {
                    profiles.put(entry.getKey(), profile);
                }
            }
            return Collections.unmodifiableMap(profiles);
        }, Collections.emptyMap());
    }

    static boolean setScale(
            final String packageName,
            final int scalePercent) {
        requirePackage(packageName);
        if (!AppPresentationProfile.isValidScale(scalePercent)) {
            throw new IllegalArgumentException(
                    "application scale must be between "
                            + AppPresentationProfile.MIN_SCALE_PERCENT
                            + " and "
                            + AppPresentationProfile.MAX_SCALE_PERCENT);
        }
        final AppPresentationProfile profile =
                new AppPresentationProfile(scalePercent);
        return DesktopStateStore.update(state ->
                state.appPresentations.put(packageName, profile));
    }

    static boolean reset(final String packageName) {
        requirePackage(packageName);
        return DesktopStateStore.update(state ->
                state.appPresentations.remove(packageName));
    }

    private static AppPresentationProfile copy(
            final AppPresentationProfile profile) {
        return profile == null
                ? null : new AppPresentationProfile(profile.scalePercent);
    }

    private static void requirePackage(final String packageName) {
        if (!isUserPackage(packageName)) {
            throw new IllegalArgumentException("invalid package name");
        }
    }

    private static boolean isUserPackage(final String packageName) {
        return AppPresentationProfile.supportsPackage(packageName);
    }
}
