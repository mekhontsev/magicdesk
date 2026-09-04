package io.github.mekhontsev.magicdesk;

/** Owns persistence and live application of per-app presentation profiles. */
final class AppPresentationProfileManager {
    private AppPresentationProfileManager() {
    }

    static void setScale(
            final String packageName,
            final int scalePercent,
            final TaskRepository.ActionCallback callback) {
        requireUserApplication(packageName);
        if (!AppPresentationProfileStore.setScale(
                packageName, scalePercent)) {
            complete(callback, false, "could not save application profile");
            return;
        }
        applyStoredProfile(packageName, callback);
    }

    static void reset(
            final String packageName,
            final TaskRepository.ActionCallback callback) {
        requireUserApplication(packageName);
        if (!AppPresentationProfileStore.reset(packageName)) {
            complete(callback, false, "could not reset application profile");
            return;
        }
        applyStoredProfile(packageName, callback);
    }

    private static void applyStoredProfile(
            final String packageName,
            final TaskRepository.ActionCallback callback) {
        if (!DesktopRuntimeBridge.getSessionSnapshot().hasHost()) {
            complete(callback, true, "application profile saved");
            return;
        }
        if (!MagicDeskRuntime.applyAppPresentation(packageName, callback)) {
            complete(
                    callback,
                    false,
                    "application profile saved; live update unavailable");
        }
    }

    static void requireUserApplication(final String packageName) {
        if (!PackageNameValidator.isSafe(packageName)) {
            throw new IllegalArgumentException("invalid package name");
        }
        if (BuildConfig.APPLICATION_ID.equals(packageName)) {
            throw new IllegalArgumentException(
                    "MagicDesk infrastructure cannot have an app profile");
        }
    }

    private static void complete(
            final TaskRepository.ActionCallback callback,
            final boolean success,
            final String message) {
        if (callback != null) {
            callback.onComplete(new TaskRepository.ActionResult(
                    success, message));
        }
    }
}
