package io.github.mekhontsev.magicdesk;

import android.content.Intent;
import android.content.pm.PackageManager;

final class AppLaunchTarget {
    final String packageName;
    final String activityClassName;
    final String action;

    private AppLaunchTarget(
            final String packageName,
            final String activityClassName,
            final String action) {
        if (!PackageNameValidator.isSafe(packageName)
                || !isSafeClassName(activityClassName)) {
            throw new IllegalArgumentException("invalid app launch target");
        }
        this.packageName = packageName;
        this.activityClassName = activityClassName;
        this.action = action == null ? "" : action;
    }

    static AppLaunchTarget explicit(
            final String packageName,
            final String activityClassName,
            final String action) {
        return new AppLaunchTarget(packageName, activityClassName, action);
    }

    static AppLaunchTarget packageDefault(final String packageName) {
        return new AppLaunchTarget(packageName, "", "");
    }

    Intent resolve(final PackageManager packageManager) {
        if (activityClassName.isEmpty()) {
            return packageManager.getLaunchIntentForPackage(packageName);
        }
        return new Intent(action.isEmpty() ? Intent.ACTION_MAIN : action)
                .setClassName(packageName, activityClassName);
    }

    static boolean isSafeClassName(final String value) {
        if (value == null) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            final char character = value.charAt(index);
            if (!(Character.isLetterOrDigit(character)
                    || character == '.'
                    || character == '_'
                    || character == '$')) {
                return false;
            }
        }
        return true;
    }
}
