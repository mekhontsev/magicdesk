package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;

/** Defines the concrete Activities that can own a desktop host task. */
final class DesktopHostComponents {
    static final String PACKAGE_NAME = BuildConfig.APPLICATION_ID;
    static final String EXTERNAL_HOME_CLASS =
            PACKAGE_NAME + ".DesktopActivity";
    static final String PHONE_HOME_CLASS =
            PACKAGE_NAME + ".PhoneHomeActivity";
    static final String INDEPENDENT_CLASS = PACKAGE_NAME + ".IndependentDesktopActivity";

    private DesktopHostComponents() {
    }

    static boolean isHostComponent(final ComponentName component) {
        return component != null
                && PACKAGE_NAME.equals(component.getPackageName())
                && isHostClassName(component.getClassName());
    }

    static boolean isHostComponentName(final String componentName) {
        return hostActivityType(componentName) != 0;
    }

    static int hostActivityType(final String componentName) {
        if (componentName == null) {
            return 0;
        }
        final int separator = componentName.indexOf('/');
        if (separator <= 0 || separator + 1 >= componentName.length()
                || !PACKAGE_NAME.equals(
                        componentName.substring(0, separator))) {
            return 0;
        }
        final String activityName = componentName.substring(separator + 1);
        return activityTypeForClass(activityName.startsWith(".")
                ? PACKAGE_NAME + activityName : activityName);
    }

    static int activityTypeForClass(final String className) {
        if (INDEPENDENT_CLASS.equals(className)) {
            return FrameworkTaskSnapshot.ACTIVITY_TYPE_STANDARD;
        }
        return EXTERNAL_HOME_CLASS.equals(className) || PHONE_HOME_CLASS.equals(className)
                ? FrameworkTaskSnapshot.ACTIVITY_TYPE_HOME : 0;
    }

    static boolean isHostClassName(final String className) {
        return activityTypeForClass(className) != 0;
    }
}
