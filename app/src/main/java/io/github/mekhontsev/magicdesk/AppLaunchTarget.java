package io.github.mekhontsev.magicdesk;

import android.content.Intent;
import android.content.pm.PackageManager;

import java.util.Objects;

public final class AppLaunchTarget {
    final String packageName;
    final String activityClassName;
    final String action;

    public String packageName() {
        return packageName;
    }

    public String activityClassName() {
        return activityClassName;
    }

    public String action() {
        return action;
    }

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

    public static AppLaunchTarget explicit(
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

    String stableKey() {
        return packageName + "|" + activityClassName + "|" + action;
    }

    boolean matchesTask(final TaskRepository.TaskEntry task) {
        return task != null && matchesTask(
                task.packageName,
                task.componentName,
                task.topActivityName);
    }

    boolean matchesTask(
            final String taskPackageName,
            final String componentName,
            final String topActivityName) {
        if (!packageName.equals(taskPackageName)) {
            return false;
        }
        if (activityClassName.length() == 0) {
            return true;
        }
        return matchesComponent(componentName)
                || matchesComponent(topActivityName);
    }

    private boolean matchesComponent(final String flattened) {
        if (flattened == null) {
            return false;
        }
        final int separator = flattened.indexOf('/');
        if (separator <= 0
                || !packageName.equals(flattened.substring(0, separator))) {
            return false;
        }
        final String rawClass = flattened.substring(separator + 1);
        final String fullClass = rawClass.startsWith(".")
                ? packageName + rawClass : rawClass;
        return activityClassName.equals(fullClass);
    }

    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AppLaunchTarget)) {
            return false;
        }
        final AppLaunchTarget target = (AppLaunchTarget) other;
        return packageName.equals(target.packageName)
                && activityClassName.equals(target.activityClassName)
                && action.equals(target.action);
    }

    @Override
    public int hashCode() {
        return Objects.hash(packageName, activityClassName, action);
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
