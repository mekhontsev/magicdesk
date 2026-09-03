package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;

/** Defines the concrete Activities that can own a desktop host task. */
final class DesktopHostComponents {
    static final String PACKAGE_NAME = BuildConfig.APPLICATION_ID;
    static final String EXTERNAL_HOME_CLASS =
            PACKAGE_NAME + ".DesktopActivity";
    static final String PHONE_HOME_CLASS =
            PACKAGE_NAME + ".PhoneDesktopHomeActivity";

    private DesktopHostComponents() {
    }

    static boolean isHostComponent(final ComponentName component) {
        return component != null
                && PACKAGE_NAME.equals(component.getPackageName())
                && isHostClassName(component.getClassName());
    }

    static boolean isHostComponentName(final String componentName) {
        if (componentName == null) {
            return false;
        }
        final int separator = componentName.indexOf('/');
        if (separator <= 0 || separator + 1 >= componentName.length()
                || !PACKAGE_NAME.equals(
                        componentName.substring(0, separator))) {
            return false;
        }
        final String activityName = componentName.substring(separator + 1);
        return isHostClassName(activityName.startsWith(".")
                ? PACKAGE_NAME + activityName : activityName);
    }

    static boolean isHostClassName(final String className) {
        return EXTERNAL_HOME_CLASS.equals(className)
                || PHONE_HOME_CLASS.equals(className);
    }
}
