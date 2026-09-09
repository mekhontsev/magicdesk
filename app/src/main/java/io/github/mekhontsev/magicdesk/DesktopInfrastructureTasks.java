package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;

/** Identifies MagicDesk tasks that support the desktop but are not applications. */
final class DesktopInfrastructureTasks {
    private static final String PACKAGE = BuildConfig.APPLICATION_ID;
    private static final String CHROME = PACKAGE + ".DesktopChromeActivity";
    private static final String BACKSTOP =
            PACKAGE + ".TaskAreaBackstopActivity";

    private DesktopInfrastructureTasks() {
    }

    static boolean isUiComponent(final ComponentName component) {
        return isClass(component, CHROME);
    }

    static boolean isUiComponentName(final String componentName) {
        return isClassName(componentName, CHROME);
    }

    static boolean isComponent(final ComponentName component) {
        return isUiComponent(component)
                || isClass(component, BACKSTOP);
    }

    static boolean isComponentName(final String componentName) {
        return isUiComponentName(componentName)
                || isClassName(componentName, BACKSTOP);
    }

    static boolean isTask(final TaskRepository.TaskEntry task) {
        return task != null
                && BuildConfig.APPLICATION_ID.equals(task.packageName)
                && (isComponentName(task.componentName)
                        || isComponentName(task.topActivityName)
                        || isAuxiliaryHome(task.home, task.displayAreaFeatureId,
                                task.componentName, task.topActivityName));
    }

    static boolean isTask(final FrameworkTaskSnapshot task) {
        return task != null
                && BuildConfig.APPLICATION_ID.equals(task.packageName)
                && (isComponentName(task.componentName)
                        || isComponentName(task.topActivityName)
                        || isAuxiliaryHome(task.isHome(), task.displayAreaFeatureId,
                                task.componentName, task.topActivityName));
    }

    static boolean isAuxiliaryHome(final boolean home, final int areaFeatureId,
            final String componentName, final String topActivityName) {
        // A desktop host lives in the default workspace. System-created HOME
        // in another area is only a delegate, never an application boundary.
        return home
                && areaFeatureId > TaskDisplayAreaHandle.Parent.DEFAULT_TASK_CONTAINER.featureId()
                && (DesktopHostComponents.isHostComponentName(componentName)
                        || DesktopHostComponents.isHostComponentName(topActivityName));
    }

    private static boolean isClass(
            final ComponentName component,
            final String className) {
        return component != null
                && PACKAGE.equals(component.getPackageName())
                && className.equals(component.getClassName());
    }

    private static boolean isClassName(
            final String componentName,
            final String className) {
        return (PACKAGE + "/" + className).equals(componentName)
                || (PACKAGE + "/."
                        + className.substring(PACKAGE.length() + 1))
                        .equals(componentName);
    }
}
