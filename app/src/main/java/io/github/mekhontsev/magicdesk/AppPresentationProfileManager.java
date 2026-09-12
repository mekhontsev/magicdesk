package io.github.mekhontsev.magicdesk;

/** Owns persistence and live application of per-app presentation profiles. */
final class AppPresentationProfileManager {
    private AppPresentationProfileManager() {
    }

    static void setScale(
            final AppIdentity application,
            final int scalePercent,
            final TaskRepository.ActionCallback callback) {
        requireUserApplication(application);
        AppProfile.requireCurrent(MagicDeskApplication.applicationContext(), application);
        if (!AppPresentationProfileStore.setScale(
                application, scalePercent)) {
            complete(callback, false, "could not save application profile");
            return;
        }
        applyStoredProfile(application, callback);
    }

    static void reset(
            final AppIdentity application,
            final TaskRepository.ActionCallback callback) {
        requireUserApplication(application);
        AppProfile.requireCurrent(MagicDeskApplication.applicationContext(), application);
        if (!AppPresentationProfileStore.reset(application)) {
            complete(callback, false, "could not reset application profile");
            return;
        }
        applyStoredProfile(application, callback);
    }

    private static void applyStoredProfile(
            final AppIdentity application,
            final TaskRepository.ActionCallback callback) {
        if (DesktopRuntimeBridge.getWorkspaces().stream().noneMatch(DesktopSessionSnapshot::hasHost)) {
            complete(callback, true, "application profile saved");
            return;
        }
        if (!MagicDeskRuntime.applyAppPresentation(application, callback)) {
            complete(
                    callback,
                    false,
                    "application profile saved; live update unavailable");
        }
    }

    static void requireUserApplication(final AppIdentity application) {
        if (application == null) {
            throw new IllegalArgumentException("application identity is required");
        }
        if (BuildConfig.APPLICATION_ID.equals(application.packageName)) {
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
