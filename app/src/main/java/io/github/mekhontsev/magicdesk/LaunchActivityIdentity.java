package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;

/** Matches an Android launch entry point and its activity-alias target. */
final class LaunchActivityIdentity {
    private final String mPackageName;
    private final ComponentName mRequestedComponent;
    private final ComponentName mResolvedComponent;
    private final boolean mPackageScoped;

    private LaunchActivityIdentity(
            final String packageName,
            final ComponentName requestedComponent,
            final ComponentName resolvedComponent,
            final boolean packageScoped) {
        if (packageName == null || packageName.isEmpty()
                || (!packageScoped
                        && (requestedComponent == null
                                || resolvedComponent == null))
                || (requestedComponent != null
                        && !packageName.equals(
                                requestedComponent.getPackageName()))
                || (resolvedComponent != null
                        && !packageName.equals(
                                resolvedComponent.getPackageName()))) {
            throw new IllegalArgumentException("invalid launch identity");
        }
        mPackageName = packageName;
        mRequestedComponent = requestedComponent;
        mResolvedComponent = resolvedComponent;
        mPackageScoped = packageScoped;
    }

    static LaunchActivityIdentity resolve(
            final PackageManager packageManager,
            final ComponentName requestedComponent) {
        ComponentName resolvedComponent = requestedComponent;
        try {
            final ActivityInfo info = packageManager.getActivityInfo(
                    requestedComponent,
                    PackageManager.ComponentInfoFlags.of(0));
            if (info.targetActivity != null
                    && !info.targetActivity.isEmpty()) {
                resolvedComponent = ComponentName.createRelative(
                        info.packageName, info.targetActivity);
            }
        } catch (PackageManager.NameNotFoundException
                | IllegalArgumentException ignored) {
            // Exact component matching remains valid for unresolved entries.
        }
        return new LaunchActivityIdentity(
                requestedComponent.getPackageName(),
                requestedComponent,
                resolvedComponent,
                false);
    }

    static LaunchActivityIdentity packageScoped(
            final String packageName,
            final ComponentName publishedComponent) {
        return new LaunchActivityIdentity(
                packageName,
                publishedComponent,
                publishedComponent,
                true);
    }

    ComponentName requestedComponent() {
        return mRequestedComponent;
    }

    boolean matches(final ComponentName observedComponent) {
        return observedComponent != null && (mPackageScoped
                ? mPackageName.equals(observedComponent.getPackageName())
                : matches(
                mPackageName,
                mRequestedComponent.getClassName(),
                mResolvedComponent.getClassName(),
                observedComponent.getPackageName(),
                observedComponent.getClassName()));
    }

    boolean matchesPackage(final ComponentName observedComponent) {
        return observedComponent != null
                && matchesPackage(observedComponent.getPackageName());
    }

    boolean matchesPackage(final String observedPackageName) {
        return matchesPackage(mPackageName, observedPackageName);
    }

    static boolean matchesPackage(
            final String packageName,
            final String observedPackageName) {
        return packageName != null && packageName.equals(observedPackageName);
    }

    static boolean matches(
            final String packageName,
            final String requestedClassName,
            final String resolvedClassName,
            final String observedPackageName,
            final String observedClassName) {
        return packageName.equals(observedPackageName)
                && (requestedClassName.equals(observedClassName)
                || resolvedClassName.equals(observedClassName));
    }
}
