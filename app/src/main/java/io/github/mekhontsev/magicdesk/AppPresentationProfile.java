package io.github.mekhontsev.magicdesk;

/** User-authored presentation preferences shared by every task of an app. */
final class AppPresentationProfile {
    static final int MIN_SCALE_PERCENT = 50;
    static final int MAX_SCALE_PERCENT = 200;
    static final int SYSTEM_SCALE_PERCENT = 100;

    final int scalePercent;

    AppPresentationProfile(final int scalePercent) {
        if (!isValidScale(scalePercent)) {
            throw new IllegalArgumentException(
                    "custom application scale is invalid");
        }
        this.scalePercent = scalePercent;
    }

    static boolean isValidScale(final int scalePercent) {
        return scalePercent >= MIN_SCALE_PERCENT
                && scalePercent <= MAX_SCALE_PERCENT;
    }

    static boolean supportsPackage(final String packageName) {
        return PackageNameValidator.isSafe(packageName)
                && !BuildConfig.APPLICATION_ID.equals(packageName);
    }
}
