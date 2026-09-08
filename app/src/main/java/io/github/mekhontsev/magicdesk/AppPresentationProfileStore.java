package io.github.mekhontsev.magicdesk;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Persists explicit per-application presentation profiles. */
final class AppPresentationProfileStore {
    private AppPresentationProfileStore() {
    }

    static AppPresentationProfile load(final AppIdentity application) {
        if (!isUserApplication(application)) {
            return null;
        }
        return DesktopStateStore.read(
                state -> copy(state.appPresentations.get(application)),
                null);
    }

    static Map<AppIdentity, AppPresentationProfile> loadAll() {
        return DesktopStateStore.read(state -> {
            final Map<AppIdentity, AppPresentationProfile> profiles =
                    new LinkedHashMap<>();
            for (final Map.Entry<AppIdentity, AppPresentationProfile> entry
                    : state.appPresentations.entrySet()) {
                final AppPresentationProfile profile = copy(entry.getValue());
                if (isUserApplication(entry.getKey()) && profile != null) {
                    profiles.put(entry.getKey(), profile);
                }
            }
            return Collections.unmodifiableMap(profiles);
        }, Collections.emptyMap());
    }

    static boolean setScale(
            final AppIdentity application,
            final int scalePercent) {
        requireApplication(application);
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
                state.appPresentations.put(application, profile));
    }

    static boolean reset(final AppIdentity application) {
        requireApplication(application);
        return DesktopStateStore.update(state ->
                state.appPresentations.remove(application));
    }

    private static AppPresentationProfile copy(
            final AppPresentationProfile profile) {
        return profile == null
                ? null : new AppPresentationProfile(profile.scalePercent);
    }

    private static void requireApplication(final AppIdentity application) {
        if (!isUserApplication(application)) {
            throw new IllegalArgumentException("invalid application identity");
        }
    }

    private static boolean isUserApplication(final AppIdentity application) {
        return application != null && AppPresentationProfile.supportsPackage(application.packageName);
    }
}
