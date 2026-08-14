package io.github.mekhontsev.magicdesk;

/** Distinguishes user-facing desktop windows from MagicDesk infrastructure. */
final class DesktopManagedTaskPolicy {
    private static final String MAGICDESK_PACKAGE =
            "io.github.mekhontsev.magicdesk";
    private static final String FILE_MANAGER_ACTIVITY =
            MAGICDESK_PACKAGE + ".FileManagerActivity";

    private DesktopManagedTaskPolicy() {
    }

    static boolean isManagedApplicationTask(
            final TaskRepository.TaskEntry task) {
        if (task == null || task.home
                || !PackageNameValidator.isSafe(task.packageName)) {
            return false;
        }
        return !MAGICDESK_PACKAGE.equals(task.packageName)
                || isFileManagerTask(task);
    }

    static boolean isFileManagerTask(
            final TaskRepository.TaskEntry task) {
        return task != null
                && MAGICDESK_PACKAGE.equals(task.packageName)
                && (hasActivity(task.componentName, FILE_MANAGER_ACTIVITY)
                        || hasActivity(
                                task.topActivityName,
                                FILE_MANAGER_ACTIVITY));
    }

    private static boolean hasActivity(
            final String componentName,
            final String expectedClass) {
        if (componentName == null) {
            return false;
        }
        final int separator = componentName.indexOf('/');
        if (separator < 0 || separator + 1 >= componentName.length()) {
            return false;
        }
        final String activity = componentName.substring(separator + 1);
        return expectedClass.equals(activity.startsWith(".")
                ? MAGICDESK_PACKAGE + activity : activity);
    }
}
