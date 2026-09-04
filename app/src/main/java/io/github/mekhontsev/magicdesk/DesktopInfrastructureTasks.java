package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;

/** Identifies MagicDesk tasks that support the desktop but are not applications. */
final class DesktopInfrastructureTasks {
    private static final String PACKAGE = BuildConfig.APPLICATION_ID;
    private static final String TASKBAR = PACKAGE + ".DesktopTaskbarActivity";
    private static final String PANELS = PACKAGE + ".DesktopPanelActivity";
    private static final String SELF_TEST_PHONE_GUARD =
            PACKAGE + ".DesktopSelfTestPhoneGuardActivity";
    private static final String BACKSTOP =
            PACKAGE + ".TaskAreaBackstopActivity";

    private DesktopInfrastructureTasks() {
    }

    static boolean isUiComponent(final ComponentName component) {
        return isClass(component, TASKBAR)
                || isClass(component, PANELS)
                || isClass(component, SELF_TEST_PHONE_GUARD);
    }

    static boolean isUiComponentName(final String componentName) {
        return isClassName(componentName, TASKBAR)
                || isClassName(componentName, PANELS)
                || isClassName(componentName, SELF_TEST_PHONE_GUARD);
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
                        || isComponentName(task.topActivityName));
    }

    static boolean isTask(final FrameworkTaskSnapshot task) {
        return task != null
                && BuildConfig.APPLICATION_ID.equals(task.packageName)
                && (isComponentName(task.componentName)
                        || isComponentName(task.topActivityName));
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
