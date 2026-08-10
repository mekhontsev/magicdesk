package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Launcher components relevant to restoring the phone UI after a desktop. */
final class PhoneHomeComponents {
    private final String mPrimary;
    private final Set<String> mSecondary;
    private final Set<String> mLauncherPackages;

    private PhoneHomeComponents(
            final String primary,
            final Set<String> secondary,
            final Set<String> launcherPackages) {
        mPrimary = primary == null ? "" : primary;
        mSecondary = Collections.unmodifiableSet(
                new LinkedHashSet<>(secondary));
        mLauncherPackages = Collections.unmodifiableSet(
                new LinkedHashSet<>(launcherPackages));
    }

    static PhoneHomeComponents resolve(final Context context) {
        if (context == null) {
            return empty();
        }
        final PackageManager packageManager = context.getPackageManager();
        final Intent primaryIntent = homeIntent(Intent.CATEGORY_HOME);
        final String primary = componentName(packageManager.resolveActivity(
                primaryIntent,
                PackageManager.MATCH_DEFAULT_ONLY
                        | PackageManager.MATCH_DISABLED_COMPONENTS));
        final Set<String> secondary = new LinkedHashSet<>();
        final Set<String> launcherPackages = new LinkedHashSet<>();
        addPackage(primary, launcherPackages);
        collectComponents(
                packageManager,
                homeIntent(Intent.CATEGORY_SECONDARY_HOME),
                secondary,
                launcherPackages,
                primary);
        return new PhoneHomeComponents(primary, secondary, launcherPackages);
    }

    static PhoneHomeComponents forTests(
            final String primary,
            final String... secondary) {
        final Set<String> secondaryComponents = new LinkedHashSet<>();
        final Set<String> packages = new LinkedHashSet<>();
        addPackage(primary, packages);
        if (secondary != null) {
            for (final String component : secondary) {
                final String normalized = normalize(component);
                if (!normalized.isEmpty()) {
                    secondaryComponents.add(normalized);
                    addPackage(normalized, packages);
                }
            }
        }
        return new PhoneHomeComponents(
                normalize(primary), secondaryComponents, packages);
    }

    String primaryComponent() {
        return mPrimary;
    }

    ComponentName primaryComponentName() {
        return mPrimary.isEmpty()
                ? null : ComponentName.unflattenFromString(mPrimary);
    }

    boolean hasPrimary() {
        return !mPrimary.isEmpty();
    }

    String diagnosticDetail() {
        return mPrimary.isEmpty()
                ? "default HOME activity unavailable"
                : "primary=" + mPrimary
                        + ", secondary=" + mSecondary.size();
    }

    String firstSecondaryClassName() {
        if (mSecondary.isEmpty()) {
            return "";
        }
        final String component = mSecondary.iterator().next();
        return component.substring(component.indexOf('/') + 1);
    }

    boolean isSecondaryTask(final TaskRepository.TaskEntry task) {
        if (task == null || !task.home) {
            return false;
        }
        return isSecondaryComponent(task.componentName)
                || isSecondaryComponent(task.topActivityName);
    }

    boolean isSecondaryComponent(final String value) {
        final String normalized = normalize(value);
        if (normalized.isEmpty() || normalized.equals(mPrimary)) {
            return false;
        }
        if (mSecondary.contains(normalized)) {
            return true;
        }
        final int separator = normalized.indexOf('/');
        return mLauncherPackages.contains(normalized.substring(0, separator))
                && normalized.substring(separator + 1)
                        .toLowerCase(Locale.ROOT)
                        .contains("secondarydisplay");
    }

    private static Intent homeIntent(final String category) {
        return new Intent(Intent.ACTION_MAIN).addCategory(category);
    }

    private static void collectComponents(
            final PackageManager packageManager,
            final Intent intent,
            final Set<String> secondary,
            final Set<String> packages,
            final String primary) {
        final List<ResolveInfo> matches = packageManager.queryIntentActivities(
                intent, PackageManager.MATCH_DISABLED_COMPONENTS);
        if (matches == null) {
            return;
        }
        for (final ResolveInfo match : matches) {
            final String component = componentName(match);
            if (component.isEmpty()) {
                continue;
            }
            addPackage(component, packages);
            if (!component.equals(primary)) {
                secondary.add(component);
            }
        }
    }

    private static String componentName(final ResolveInfo resolveInfo) {
        final ActivityInfo activityInfo = resolveInfo == null
                ? null : resolveInfo.activityInfo;
        return activityInfo == null
                        || activityInfo.packageName == null
                        || activityInfo.name == null
                ? ""
                : new ComponentName(
                        activityInfo.packageName,
                        activityInfo.name).flattenToString();
    }

    private static String normalize(final String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        final int separator = value.indexOf('/');
        if (separator <= 0 || separator == value.length() - 1) {
            return "";
        }
        final String packageName = value.substring(0, separator);
        String className = value.substring(separator + 1);
        if (className.startsWith(".")) {
            className = packageName + className;
        }
        return PackageNameValidator.isSafe(packageName)
                        && AppLaunchTarget.isSafeClassName(className)
                ? packageName + "/" + className : "";
    }

    private static void addPackage(
            final String componentName,
            final Set<String> packages) {
        final String normalized = normalize(componentName);
        if (!normalized.isEmpty()) {
            packages.add(normalized.substring(0, normalized.indexOf('/')));
        }
    }

    private static PhoneHomeComponents empty() {
        return new PhoneHomeComponents(
                "", Collections.emptySet(), Collections.emptySet());
    }
}
