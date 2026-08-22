package io.github.mekhontsev.magicdesk;

import android.content.ComponentName;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;

/** Matches an Android launch entry point and its activity-alias target. */
final class LaunchActivityIdentity {
    private final ComponentName mRequestedComponent;
    private final ComponentName mResolvedComponent;

    LaunchActivityIdentity(
            final ComponentName requestedComponent,
            final ComponentName resolvedComponent) {
        if (requestedComponent == null || resolvedComponent == null) {
            throw new IllegalArgumentException("missing launch component");
        }
        mRequestedComponent = requestedComponent;
        mResolvedComponent = resolvedComponent;
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
                requestedComponent, resolvedComponent);
    }

    ComponentName requestedComponent() {
        return mRequestedComponent;
    }

    boolean matches(final ComponentName observedComponent) {
        return observedComponent != null && matches(
                mRequestedComponent.getPackageName(),
                mRequestedComponent.getClassName(),
                mResolvedComponent.getClassName(),
                observedComponent.getPackageName(),
                observedComponent.getClassName());
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
