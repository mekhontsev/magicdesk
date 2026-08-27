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
    private final String mPrimaryProcess;
    private final Set<String> mSecondary;
    private final Set<String> mLauncherPackages;

    private PhoneHomeComponents(
            final String primary,
            final String primaryProcess,
            final Set<String> secondary,
            final Set<String> launcherPackages) {
        mPrimary = primary == null ? "" : primary;
        mPrimaryProcess = primaryProcess == null ? "" : primaryProcess;
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
        final ResolveInfo primaryMatch = packageManager.resolveActivity(
                primaryIntent, PackageManager.MATCH_DEFAULT_ONLY);
        final String primary = componentName(primaryMatch);
        final String primaryProcess = processName(primaryMatch);
        final Set<String> secondary = new LinkedHashSet<>();
        final Set<String> launcherPackages = new LinkedHashSet<>();
        addPackage(primary, launcherPackages);
        collectComponents(
                packageManager,
                homeIntent(Intent.CATEGORY_SECONDARY_HOME),
                secondary,
                launcherPackages,
                primary);
        return new PhoneHomeComponents(
                primary, primaryProcess, secondary, launcherPackages);
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
                normalize(primary),
                packageName(primary),
                secondaryComponents,
                packages);
    }

    String primaryComponent() {
        return mPrimary;
    }

    String primaryPackage() {
        return packageName(mPrimary);
    }

    String primaryProcess() {
        return mPrimaryProcess;
    }

    ComponentName primaryComponentName() {
        return mPrimary.isEmpty()
                ? null : ComponentName.unflattenFromString(mPrimary);
    }

    boolean hasPrimary() {
        return !mPrimary.isEmpty();
    }

    boolean isPrimaryComponent(final ComponentName component) {
        return component != null
                && isPrimaryComponent(
                        component.getPackageName(), component.getClassName());
    }

    boolean isPrimaryComponent(
            final String packageName,
            final String className) {
        return packageName != null
                && className != null
                && normalize(packageName + "/" + className).equals(mPrimary);
    }

    boolean isPrimaryProcess(final String processName) {
        if (processName == null || mPrimary.isEmpty()) {
            return false;
        }
        if (!mPrimaryProcess.isEmpty()
                && mPrimaryProcess.equals(processName)) {
            return true;
        }
        final String primaryPackage = packageName(mPrimary);
        final int processSeparator = processName.indexOf(':');
        final String processPackage = processSeparator < 0
                ? processName : processName.substring(0, processSeparator);
        return primaryPackage.equals(processPackage);
    }

    boolean isPrimaryHomeStart(
            final Intent intent,
            final String targetPackage) {
        if (intent == null) {
            return false;
        }
        final ComponentName component = intent.getComponent();
        return isPrimaryHomeStart(
                component == null ? null : component.getPackageName(),
                component == null ? null : component.getClassName(),
                intent.getAction(),
                intent.hasCategory(Intent.CATEGORY_HOME),
                targetPackage);
    }

    boolean isPrimaryHomeStart(
            final String componentPackage,
            final String componentClass,
            final String action,
            final boolean homeCategory,
            final String targetPackage) {
        return isPrimaryComponent(componentPackage, componentClass)
                || (isPrimaryPackage(targetPackage)
                        && Intent.ACTION_MAIN.equals(action)
                        && homeCategory);
    }

    boolean isPrimaryPackage(final String packageName) {
        return packageName != null
                && packageName(mPrimary).equals(packageName);
    }

    String diagnosticDetail() {
        return mPrimary.isEmpty()
                ? "default HOME activity unavailable"
                : "primary=" + mPrimary
                        + ", process=" + mPrimaryProcess
                        + ", secondary=" + mSecondary.size();
    }

    boolean hasSecondaryHomeOnTop(
            final TaskRepository.TaskEntry task) {
        if (task == null || !task.home) {
            return false;
        }
        return isSecondaryComponent(task.topActivityName);
    }

    boolean isDedicatedSecondaryTask(
            final TaskRepository.TaskEntry task) {
        return task != null && isDedicatedSecondaryTask(
                task.home, task.componentName, task.topActivityName);
    }

    boolean isDedicatedSecondaryTask(
            final boolean home,
            final String componentName,
            final String topActivityName) {
        return home
                && isSecondaryComponent(topActivityName)
                && isSecondaryComponent(componentName);
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

    private static String processName(final ResolveInfo resolveInfo) {
        final ActivityInfo activityInfo = resolveInfo == null
                ? null : resolveInfo.activityInfo;
        if (activityInfo == null) {
            return "";
        }
        return activityInfo.processName == null
                ? activityInfo.packageName : activityInfo.processName;
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

    private static String packageName(final String componentName) {
        final String normalized = normalize(componentName);
        return normalized.isEmpty()
                ? "" : normalized.substring(0, normalized.indexOf('/'));
    }

    private static PhoneHomeComponents empty() {
        return new PhoneHomeComponents(
                "", "", Collections.emptySet(), Collections.emptySet());
    }
}
