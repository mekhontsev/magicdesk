package io.github.mekhontsev.magicdesk;

/** Distinguishes user-facing desktop windows from MagicDesk infrastructure. */
final class DesktopManagedTaskPolicy {
    private static final String MAGICDESK_PACKAGE = BuildConfig.APPLICATION_ID;

    private DesktopManagedTaskPolicy() {
    }

    static boolean isManagedApplicationTask(
            final TaskRepository.TaskEntry task) {
        if (task == null || task.home
                || !PackageNameValidator.isSafe(task.packageName)) {
            return false;
        }
        return !MAGICDESK_PACKAGE.equals(task.packageName)
                || BuiltInDesktopAppCatalog.isManagedTask(task);
    }
}
