package io.github.mekhontsev.magicdesk;

import android.content.Intent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Explicit launch provenance shared by all workspace activity-start guards. */
final class ShellActivityLaunchScope implements AutoCloseable {
    private static final Set<ShellActivityLaunchScope> ACTIVE =
            ConcurrentHashMap.newKeySet();

    private final LaunchActivityIdentity mIdentity;

    private ShellActivityLaunchScope(final LaunchActivityIdentity identity) {
        mIdentity = java.util.Objects.requireNonNull(identity);
    }

    static ShellActivityLaunchScope begin(final LaunchActivityIdentity identity) {
        final ShellActivityLaunchScope scope = new ShellActivityLaunchScope(identity);
        ACTIVE.add(scope);
        return scope;
    }

    static boolean isActive(final Intent intent, final String packageName) {
        // IActivityController callbacks arrive on Binder threads without the
        // caller or display options. Intent flags are not launch provenance.
        for (final ShellActivityLaunchScope scope : ACTIVE) {
            if (scope.mIdentity.matchesStart(intent, packageName)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        ACTIVE.remove(this);
    }
}
