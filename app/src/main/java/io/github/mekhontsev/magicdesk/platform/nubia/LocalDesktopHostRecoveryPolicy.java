package io.github.mekhontsev.magicdesk.platform.nubia;

import io.github.mekhontsev.magicdesk.DesktopActivity;

import io.github.mekhontsev.magicdesk.TaskRepository;

import android.view.Display;

import java.util.List;

/** Recognizes Nubia returning to Home after its last local freeform task closes. */
final class LocalDesktopHostRecoveryPolicy {
    private LocalDesktopHostRecoveryPolicy() {
    }

    static boolean shouldRestore(
            final int displayId,
            final List<TaskRepository.TaskEntry> tasks,
            final String desktopPackage) {
        if (displayId != Display.DEFAULT_DISPLAY
                || tasks == null
                || desktopPackage == null) {
            return false;
        }
        boolean visibleHome = false;
        boolean desktopHostPresent = false;
        boolean desktopHostActive = false;
        boolean visibleApplication = false;
        for (final TaskRepository.TaskEntry task : tasks) {
            if (task == null || task.displayId != displayId) {
                continue;
            }
            if (task.visible && task.home) {
                visibleHome = true;
            }
            if (isDesktopHost(task, desktopPackage)) {
                desktopHostPresent = true;
                desktopHostActive |= task.active;
            } else if (task.visible
                    && !task.home
                    && !desktopPackage.equals(task.packageName)) {
                visibleApplication = true;
            }
        }
        return visibleHome
                && desktopHostPresent
                && !desktopHostActive
                && !visibleApplication;
    }

    private static boolean isDesktopHost(
            final TaskRepository.TaskEntry task,
            final String desktopPackage) {
        return desktopPackage.equals(task.packageName)
                && task.componentName != null
                && (task.componentName.endsWith("/.DesktopActivity")
                        || task.componentName.endsWith(
                                "/" + desktopPackage + ".DesktopActivity"));
    }
}
